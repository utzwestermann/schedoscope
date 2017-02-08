/**
  * Copyright 2015 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.schedoscope.scheduler.actors

import java.lang.Math.pow

import akka.actor.{Actor, ActorRef, Props, actorRef2Scala}
import akka.event.{Logging, LoggingReceive}
import org.joda.time.LocalDateTime
import org.schedoscope.conf.SchedoscopeSettings
import org.schedoscope.dsl.transformations.Touch
import org.schedoscope.dsl.{ExternalView, View}
import org.schedoscope.scheduler.messages._
import org.schedoscope.scheduler.states._

import scala.collection.{Set, mutable}
import scala.concurrent.duration.Duration

class TableActor(currentStates: Map[View, ViewSchedulingState],
                 settings: SchedoscopeSettings,
                 dependencies: Map[String, ActorRef],
                 viewManagerActor: ActorRef,
                 transformationManagerActor: ActorRef,
                 schemaManagerRouter: ActorRef,
                 viewSchedulingListenerManagerActor: ActorRef
                ) extends Actor {

  import context._

  val stateMachine: ViewSchedulingStateMachine = new ViewSchedulingStateMachineImpl

  val log = Logging(system, this)
  var knownDependencies = dependencies
  val viewStates = mutable.HashMap(currentStates.map {
    case (view, state) => view.urlPath -> state
  }.toSeq: _*)


  def receive: Receive = LoggingReceive {

    case CommandForView(sourceView, targetView, command) => {

      //mark the currentState as implicit for calling the stateTransition
      implicit val currentState = viewStates(targetView.urlPath)

      val senderRef = AkkaActor(sourceView, sender)

      command match {

        case MaterializeView(mode) => stateTransition {
          stateMachine.materialize(currentState, senderRef, mode)
        }

        case MaterializeExternalView(mode) => {
          val currentView = currentState.view
          //update state if external and NoOp view
          schemaManagerRouter ! GetMetaDataForMaterialize(currentView, mode, senderRef)
        }

        case MetaDataForMaterialize(metadata, mode, source) => stateTransition {
          //
          //Got an answer about the state of an external view -> use it to execute the NoOp materialisation
          //
          val externalState = metadata match {
            case (view, (version, timestamp)) =>
              val v = if (view.isInstanceOf[ExternalView]) view else ExternalView(view)
              ViewManagerActor.getStateFromMetadata(v, version, timestamp)
          }
          stateMachine.materialize(externalState, source, mode)
        }

        case InvalidateView() => stateTransition {
          stateMachine.invalidate(currentState, senderRef)
        }

        case ViewHasNoData(dependency) => stateTransition {
          stateMachine.noDataAvailable(currentState.asInstanceOf[Waiting], dependency)
        }

        case ViewFailed(dependency) => stateTransition {
          stateMachine.failed(currentState.asInstanceOf[Waiting], dependency)
        }

        case ViewMaterialized(dependency, incomplete, transformationTimestamp, withErrors) => stateTransition {
          //          val head = currentState.asInstanceOf[Waiting].dependenciesMaterializing.head
          stateMachine.materialized(currentState.asInstanceOf[Waiting], dependency, transformationTimestamp, withErrors, incomplete)
        }

        case s: TransformationSuccess[_] => stateTransition {
          stateMachine.transformationSucceeded(currentState.asInstanceOf[Transforming], !s.viewHasData)
        }

        case _: TransformationFailure[_] => stateTransition {
          val s = currentState.asInstanceOf[Transforming]
          val result = stateMachine.transformationFailed(s)
          val retry = s.retry

          result.currentState match {
            case _: Retrying =>
              system
                .scheduler
                .scheduleOnce(Duration.create(pow(2, retry).toLong, "seconds")) {
                  self ! CommandForView(None, targetView, Retry())
                }

              result

            case _ => result
          }
        }

        case Retry() => stateTransition {
          stateMachine.retry(currentState.asInstanceOf[Retrying])
        }
      }
    }

    case NewViewActorRef(view: View, viewRef: ActorRef) => {
      knownDependencies += view.tableName -> viewRef
    }

    case other =>
      log.error(s"Illegal Message received by TableActor: $other")
      //TODO: maybe delete after debug phase
      throw new IllegalArgumentException(s"Illegal Message received by TableActor")

  }


  def stateTransition(messageApplication: ResultingViewSchedulingState)
                     (implicit currentState: ViewSchedulingState) = messageApplication match {
    case ResultingViewSchedulingState(updatedState, actions) => {

      val currentView = currentState.view
      val previousState = currentState

      viewStates.put(currentView.urlPath, updatedState)
      performSchedulingActions(actions)

      notifySchedulingListeners(previousState, updatedState, actions)

      if (stateChange(previousState, updatedState))
        communicateStateChange(updatedState, previousState)

    }
  }

  def notifySchedulingListeners(previousState: ViewSchedulingState,
                                newState: ViewSchedulingState,
                                actions: scala.collection.immutable.Set[ViewSchedulingAction]) =
    if (!previousState.view.isExternal) {
      viewManagerActor ! ViewSchedulingMonitoringEvent(previousState, newState,
        actions, new LocalDateTime())
      viewSchedulingListenerManagerActor ! ViewSchedulingMonitoringEvent(previousState, newState,
        actions, new LocalDateTime())

    }

  def performSchedulingActions(actions: Set[ViewSchedulingAction])
                              (implicit currentState: ViewSchedulingState) = {

    implicit val reportingView = currentState.view

    actions.foreach {

      case WriteTransformationTimestamp(view, transformationTimestamp) =>
        if (!view.isExternal) schemaManagerRouter ! LogTransformationTimestamp(view, transformationTimestamp)

      case WriteTransformationCheckum(view) =>
        if (!view.isExternal) schemaManagerRouter ! SetViewVersion(view)

      case TouchSuccessFlag(view) =>
        touchSuccessFlag(view)

      case Materialize(view, mode) =>
        if (!view.isExternal) {
          sendMessageToView(view, MaterializeView(mode))
        } else {
          sendMessageToView(view, MaterializeExternalView(mode))
        }

      case Transform(view) =>
        transformationManagerActor ! view

      case ReportNoDataAvailable(view, listeners) =>
        listeners.foreach {
          sendMessageToListener(_, ViewHasNoData(view))
        }

      case ReportFailed(view, listeners) =>
        listeners.foreach {
          sendMessageToListener(_, ViewFailed(view))
        }

      case ReportInvalidated(view, listeners) =>
        listeners.foreach {
          sendMessageToListener(_, ViewStatusResponse("invalidated", view, self))
        }

      case ReportNotInvalidated(view, listeners) =>
        log.warning(s"VIEWACTOR: Could not invalidate view ${view}")

      case ReportMaterialized(view, listeners, transformationTimestamp, withErrors, incomplete) =>
        listeners.foreach { l =>
          sendMessageToListener(l, ViewMaterialized(view, incomplete, transformationTimestamp, withErrors))
        }
    }
  }

  def sendMessageToListener(listener: PartyInterestedInViewSchedulingStateChange, msg: AnyRef)
                           (implicit reportingView: View): Unit =
    listener match {
      case DependentView(view) => sendMessageToView(view, msg)

      case AkkaActor(Some(view), actorRef) => actorRef ! CommandForView(Some(reportingView), view, msg)

      case AkkaActor(None, actorRef) => actorRef ! msg

      case _ => //Not supported
    }

  def sendMessageToView(view: View, msg: AnyRef)(implicit reportingView: View) = {

    //Package Message:
    val messageForView = CommandForView(Some(reportingView), view, msg)

    //
    // New dependencies may appear at a start of a new day. These might not yet have corresponding
    // actors created by the ViewManagerActor. Take care that a view actor is available before
    // returning an actor selection for it, as messages might end up in nirvana otherwise.
    //
    knownDependencies.get(view.tableName) match {
      case Some(r: ActorRef) =>
        r ! messageForView
      case None =>
        viewManagerActor ! DelegateMessageToView(view, messageForView)
    }

    //    ViewManagerActor.actorForView(view)
  }

  def touchSuccessFlag(view: View) {
    actorOf(Props(new Actor {
      override def preStart() {
        transformationManagerActor ! Touch(view.fullPath + "/_SUCCESS")
      }

      def receive = {
        case _ => context stop self
      }
    }))
  }

  def stateChange(currentState: ViewSchedulingState, updatedState: ViewSchedulingState) = currentState.getClass != updatedState.getClass

  def communicateStateChange(newState: ViewSchedulingState, previousState: ViewSchedulingState) {
    val vsr = newState match {
      case Waiting(view, _, _, _, _, _, _, withErrors, incomplete, _) =>
        ViewStatusResponse(newState.label, view, self, Some(withErrors), Some(incomplete))
      case Transforming(view, _, _, _, withErrors, incomplete, _) =>
        ViewStatusResponse(newState.label, view, self, Some(withErrors), Some(incomplete))
      case Materialized(view, _, _, withErrors, incomplete) =>
        ViewStatusResponse(newState.label, view, self, Some(withErrors), Some(incomplete))
      case Retrying(view, _, _, _, withErrors, incomplete, _) =>
        ViewStatusResponse(newState.label, view, self, Some(withErrors), Some(incomplete))
      case _ => ViewStatusResponse(newState.label, newState.view, self)
    }

    viewManagerActor ! vsr
  }
}

object TableActor {
  def props(states: Map[View, ViewSchedulingState],
            settings: SchedoscopeSettings,
            dependencies: Map[String, ActorRef],
            viewManagerActor: ActorRef,
            transformationManagerActor: ActorRef,
            schemaManagerRouter: ActorRef,
            viewSchedulingListenerManagerActor: ActorRef): Props =
    Props(classOf[TableActor],
      states: Map[View, ViewSchedulingState],
      settings,
      dependencies,
      viewManagerActor,
      transformationManagerActor,
      schemaManagerRouter,
      viewSchedulingListenerManagerActor).withDispatcher("akka.actor.views-dispatcher")

}

