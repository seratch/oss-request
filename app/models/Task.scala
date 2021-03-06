/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package models

import java.net.URL
import java.time.ZonedDateTime

import io.getquill.MappedEncoding
import models.Task.CompletableByType
import play.api.libs.functional.syntax._
import play.api.libs.json._
import utils.MarkdownTransformer

case class Task(id: Int, createDate: ZonedDateTime, completableBy: Seq[String], completedBy: Option[String], completedDate: Option[ZonedDateTime], completionMessage: Option[String], state: State.State, prototype: Task.Prototype, data: Option[JsObject], requestSlug: String) {
  val completableByEmailsOrUrl: Either[Set[String], URL] = {
    require(completableBy.nonEmpty)
    if (!prototype.completableBy.exists(_.`type` == CompletableByType.Service)) {
      Left(completableBy.toSet)
    }
    else {
      Right(new URL(completableBy.head))
    }
  }
  val maybeServiceKey: Option[String] = prototype.completableBy.filter(_.`type` == CompletableByType.Service).flatMap(_.value)

  def stateToHuman: String = prototype.`type` match {
    case Task.TaskType.Approval =>
      state match {
        case State.InProgress => "in review"
        case State.OnHold => "put on hold"
        case State.Cancelled => "denied"
        case State.Completed => "approved"
      }
    case Task.TaskType.Action =>
      state match {
        case State.InProgress => "in progress"
        case State.OnHold => "put on hold"
        case State.Cancelled => "cancelled"
        case State.Completed => "completed"
      }
    case Task.TaskType.Input =>
      state match {
        case State.InProgress => "awaiting input"
        case State.OnHold => "put on hold"
        case State.Cancelled => "cancelled"
        case State.Completed => "completed"
      }
  }

}

object Task {

  case class Prototype(label: String, `type`: TaskType.TaskType, info: String, completableBy: Option[CompletableBy] = None, form: Option[JsObject] = None, taskEvents: Seq[TaskEvent] = Seq.empty[TaskEvent], dependencies: Set[String] = Set.empty[String], approvalConditions: Set[String] = Set.empty[String]) {
    lazy val infoMarkdownToHtml = {
      MarkdownTransformer.transform(info)
    }
  }

  object TaskType extends Enumeration {
    type TaskType = Value

    val Approval = Value("APPROVAL")
    val Action = Value("ACTION")
    val Input = Value("INPUT")

    implicit val jsonReads = Reads[TaskType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[TaskType]](JsError("Could not find that type"))(JsSuccess(_))
    }

    implicit val encodeType = MappedEncoding[TaskType, String](_.toString)
    implicit val decodeType = MappedEncoding[String, TaskType](TaskType.withName)
  }

  case class CompletableBy(`type`: CompletableByType.CompletableByType, value: Option[String])

  object CompletableByType extends Enumeration {
    type CompletableByType = Value

    val Email = Value("EMAIL")
    val Group = Value("GROUP")
    val Service = Value("SERVICE")

    implicit val jsonReads = Reads[CompletableByType] { jsValue =>
      values.find(_.toString == jsValue.as[String]).fold[JsResult[CompletableByType]](JsError("Could not find that type"))(JsSuccess(_))
    }

    implicit val encodeType = MappedEncoding[CompletableByType, String](_.toString)
    implicit val decodeType = MappedEncoding[String, CompletableByType](CompletableByType.withName)
  }

  object CompletableBy {
    implicit val jsonReads = Json.reads[CompletableBy]
    implicit val jsonWrites = Json.writes[CompletableBy]
  }

  object Prototype {
    implicit val jsonReads = (
      (__ \ "label").read[String] ~
      (__ \ "type").read[TaskType.TaskType] ~
      (__ \ "info").read[String] ~
      (__ \ "completable_by").readNullable[CompletableBy] ~
      (__ \ "form").readNullable[JsObject] ~
      (__ \ "task_events").readNullable[Seq[TaskEvent]].map(_.getOrElse(Seq.empty[TaskEvent])) ~
      (__ \ "dependencies").readNullable[Set[String]].map(_.getOrElse(Set.empty[String])) ~
      (__ \ "approval_conditions").readNullable[Set[String]].map(_.getOrElse(Set.empty[String]))
    )(Prototype.apply _)
    implicit val jsonWrites = (
      (__ \ "label").write[String] ~
      (__ \ "type").write[TaskType.TaskType] ~
      (__ \ "info").write[String] ~
      (__ \ "completable_by").writeNullable[CompletableBy] ~
      (__ \ "form").writeNullable[JsObject] ~
      (__ \ "task_events").write[Seq[TaskEvent]] ~
      (__ \ "dependencies").write[Set[String]] ~
      (__ \ "approval_conditions").write[Set[String]]
    )(unlift(Prototype.unapply))
    implicit val prototypeEncoder = MappedEncoding[Task.Prototype, String](prototype => Json.toJson(prototype).toString())
    implicit val prototypeDecoder = MappedEncoding[String, Task.Prototype](Json.parse(_).as[Task.Prototype])
  }

  implicit val jsonReads = Json.reads[Task]
  implicit val jsonWrites = Json.writes[Task]

}

object Tasks {

  type TaskTitle = String
  type Message = String

  def conditionalApprovals(tasks: Seq[Task]): Seq[(TaskTitle, Message)] = {
    for {
      task <- tasks
      if task.state == State.Completed
      if task.prototype.`type` == Task.TaskType.Approval
      if task.completableByEmailsOrUrl.isLeft
      message <- task.completionMessage
    } yield task.prototype.label -> message
  }

}
