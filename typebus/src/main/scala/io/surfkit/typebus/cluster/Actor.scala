package io.surfkit.typebus.cluster

import java.util.UUID

object Actor {
  trait ActorSharding {
    val numberOfShards = 50   // should be factor of 10 greater the num nodes.

    trait Command {
      def entityId: String
    }

    final case class Get(entityId: String) extends  Command
    final case class ShardMessage(entityId: String, payload: Any) extends Command
  }

  // This provides the inverse of a compose.
  implicit class RichPartial[-B, C](val f: PartialFunction[B, C]) {
    def composePartial[A](g: Function[A, B]): PartialFunction[A, C] = new PartialFunction[A, C] {
      override def isDefinedAt(x: A): Boolean =
        f.isDefinedAt(g(x))

      override def apply(x: A): C =
        f(g(x))
    }
  }

}