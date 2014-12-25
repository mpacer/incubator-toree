/*
 * Copyright 2014 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.spark.kernel.protocol.v5.socket

import akka.actor.Actor
import akka.zeromq.ZMQMessage
import com.ibm.spark.comm.{CommStorage, CommRegistrar, ClientCommWriter}
import com.ibm.spark.kernel.protocol.v5.MessageType.MessageType
import com.ibm.spark.kernel.protocol.v5.Utilities._
import com.ibm.spark.kernel.protocol.v5.client.execution.DeferredExecutionManager
import com.ibm.spark.kernel.protocol.v5.content._
import com.ibm.spark.kernel.protocol.v5.{ActorLoader, KMBuilder, KernelMessage, MessageType}
import com.ibm.spark.utils.LogLike

import scala.util.Failure

/**
 * The client endpoint for IOPub messages specified in the IPython Kernel Spec
 * @param socketFactory A factory to create the ZeroMQ socket connection
 */
class IOPubClient(
  socketFactory: ClientSocketFactory, actorLoader: ActorLoader,
  commRegistrar: CommRegistrar, commStorage: CommStorage
) extends Actor with LogLike {
  private val PARENT_HEADER_NULL_MESSAGE = "Parent Header was null in Kernel Message."
  private val socket = socketFactory.IOPubClient(context.system, self)
  logger.info("Created IOPub socket")

  private def receiveKernelMessage(
    kernelMessage: KernelMessage, func: (String) => Unit
  ): Unit = {
    if(kernelMessage.parentHeader != null){
      func(kernelMessage.parentHeader.msg_id)
    } else {
      logger.warn("Received message with null parent header.")
      logger.debug(s"Kernel message is: $kernelMessage")
      sender().forward(Failure(new RuntimeException(PARENT_HEADER_NULL_MESSAGE)))
    }
  }

  private def receiveStreamMessage(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    // look up callback in CallbackMap based on msg_id and invoke
    val optionalDE = DeferredExecutionManager.get(parentHeaderId)
    optionalDE match {
      case Some(de) => parseAndHandle(kernelMessage.contentString,
        StreamContent.streamContentReads,
        (streamContent: StreamContent) => de.emitStreamContent(streamContent))
      case None =>
        logger.warn(s"No deferred execution found for id $parentHeaderId")
    }
  }

  private def receiveExecuteResult(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    // look up callback in CallbackMap based on msg_id and invoke
    val optionalDE = DeferredExecutionManager.get(parentHeaderId)
    optionalDE match {
      case Some(de) => parseAndHandle(kernelMessage.contentString,
        ExecuteResult.executeResultReads,
        (executeResult: ExecuteResult) => de.resolveResult(executeResult))
      case None =>
        logger.warn(s"No deferred execution found for id $parentHeaderId")
    }
  }

  private def receiveCommOpen(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommOpen.commOpenReads,
      (commOpen: CommOpen) => {
        val targetName = commOpen.target_name
        val commId = commOpen.comm_id

        if (commStorage.contains(targetName)) {
          val commWriter = new ClientCommWriter(actorLoader, KMBuilder(), commId)
          commStorage(targetName).executeOpenCallbacks(
            commWriter, commId, targetName, commOpen.data)
          commRegistrar.link(targetName, commId)
        }
      }
    )
  }

  private def receiveCommMsg(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommMsg.commMsgReads,
      (commMsg: CommMsg) => {
        val commId = commMsg.comm_id

        if (commStorage.contains(commId)) {
          val commWriter = new ClientCommWriter(actorLoader, KMBuilder(), commId)
          commStorage(commId).executeMsgCallbacks(commWriter, commId, commMsg.data)
        }
      }
    )
  }

  private def receiveCommClose(
    parentHeaderId:String, kernelMessage: KernelMessage
  ): Unit = {
    parseAndHandle(
      kernelMessage.contentString,
      CommClose.commCloseReads,
      (commClose: CommClose) => {
        val commId = commClose.comm_id

        if (commStorage.contains(commId)) {
          val commWriter = new ClientCommWriter(actorLoader, KMBuilder(), commId)
          commStorage(commId).executeCloseCallbacks(
            commWriter, commId, commClose.data)
          commRegistrar.remove(commId)
        }
      }
    )
  }

  override def receive: Receive = {
    case message: ZMQMessage =>
      // convert to KernelMessage using implicits in v5
      logger.debug("Received IOPub kernel message.")
      val kernelMessage: KernelMessage = message
      logger.trace(s"Kernel message is $kernelMessage")
      val messageType: MessageType =
        MessageType.withName(kernelMessage.header.msg_type)

      messageType match {
        case MessageType.ExecuteResult => receiveKernelMessage(
          kernelMessage, receiveExecuteResult(_, kernelMessage))
        case MessageType.Stream => receiveKernelMessage(
          kernelMessage, receiveStreamMessage(_, kernelMessage))
        case MessageType.CommOpen => receiveKernelMessage(
          kernelMessage, receiveCommOpen(_, kernelMessage))
        case MessageType.CommMsg => receiveKernelMessage(
          kernelMessage, receiveCommMsg(_, kernelMessage))
        case MessageType.CommClose => receiveKernelMessage(
          kernelMessage, receiveCommClose(_, kernelMessage))
        case any =>
          logger.warn(s"Received unhandled MessageType $any")
      }
  }
}
