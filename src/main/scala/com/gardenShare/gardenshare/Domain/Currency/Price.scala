package com.gardenShare.gardenshare

import cats.Monoid
import cats.Show
import cats.kernel.Hash
import cats.kernel.Order
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Price(value: Int)

object Price {
  def unapply(st: String): Option[Price] = st.toIntOption.map(f => Price(f))

  implicit lazy final val priceCodec: Codec[Price] = deriveCodec
}
