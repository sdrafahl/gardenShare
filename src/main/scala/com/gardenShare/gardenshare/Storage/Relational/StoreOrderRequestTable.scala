package com.gardenShare.gardenshare.Storage.Relational

import slick.jdbc.PostgresProfile.api._
import slick.dbio.DBIOAction
import slick.lifted.AbstractTable

import cats.effect.IO
import com.gardenShare.gardenshare.StoreOrderRequest
import cats.effect.ContextShift
import cats.implicits._
import com.gardenShare.gardenshare.UserEntities.Email
import com.gardenShare.gardenshare.StoreOrderRequestWithId
import java.time.ZonedDateTime
import scala.util.Try
import com.gardenShare.gardenshare.ParseZoneDateTime

object StoreOrderRequestTable {
  class StoreOrderRequestTable(tag: Tag) extends Table[(Int, String, String, String)](tag, "storeorderrequest") {
    def storeRequestId = column[Int]("storeorderrequestid", O.PrimaryKey, O.AutoInc)
    def sellerEmail = column[String]("selleremail")
    def buyerEmail = column[String]("buyeremail")
    def datesubmited = column[String]("datesubmited")    
    def *  = (storeRequestId, sellerEmail, buyerEmail, datesubmited)
  }
  val storeOrderRequests = TableQuery[StoreOrderRequestTable]
}

object ProductReferenceTable {
  class ProductReferenceTable(tag: Tag) extends Table[(Int, Int)](tag, "productreferencetable") {
    def productReferenceTableId = column[Int]("productreferencetableid")
    // todo: need to add quantity
    def productId = column[Int]("productid")
    def * = (productReferenceTableId, productId)
  }
  val productReferenceTable = TableQuery[ProductReferenceTable]
}

abstract class DeleteStoreOrderRequestsForSeller[F[_]] {
  def delete(e: Email)(implicit cs: ContextShift[F]): F[Unit]
}

object DeleteStoreOrderRequestsForSeller {
  implicit def createIODeleteStoreOrderRequestsForSeller(implicit getter: GetStoreOrderRequestsWithSellerEmail[IO]) = new DeleteStoreOrderRequestsForSeller[IO] {
    def delete(e: Email)(implicit cs: ContextShift[IO]): IO[Unit] = {

      def createDeleteOrdersByIdQuery(id: Int) = {
        for {
          a <- StoreOrderRequestTable.storeOrderRequests if a.storeRequestId === id
        } yield a
      }

      def createQueryFoProductReferences(prodctReferenceTableId: Int) = {
        for {
          a <- ProductReferenceTable.productReferenceTable if a.productReferenceTableId === prodctReferenceTableId
        } yield a
      }

      for {
        orders <- getter.getWithEmail(e)
        _ <- orders.map{order =>
          val pgmToDeleteProductReferences = order.storeOrderRequest.products.map(prod => (IO.fromFuture(IO(Setup.db.run(createQueryFoProductReferences(prod.id).delete))))).parSequence
          val pgmToDeleteOrderRequests =  IO.fromFuture(IO(Setup.db.run(createDeleteOrdersByIdQuery(order.id).delete)))
          pgmToDeleteProductReferences &> pgmToDeleteOrderRequests
        }.parSequence
      } yield ()
    }
  }
}

abstract class InsertStoreOrderRequest[F[_]] {
  def insertStoreOrderRequest(req: StoreOrderRequest)(implicit cs: ContextShift[F]): F[StoreOrderRequestWithId]
}

object InsertStoreOrderRequest {
  implicit def createIOInsertStoreOrderRequest = new InsertStoreOrderRequest[IO] {
    def insertStoreOrderRequest(req: StoreOrderRequest)(implicit cs: ContextShift[IO]): IO[StoreOrderRequestWithId] = {
      val storeOrderRequestTable = StoreOrderRequestTable.storeOrderRequests
      val qu = StoreOrderRequestTable.storeOrderRequests.returning(storeOrderRequestTable)
      val res = qu += (0, req.seller.underlying, req.buyer.underlying, req.dateSubmitted.toString())
      IO.fromFuture(IO(Setup.db.run(res))).flatMap{a =>
        val prodRefTable = ProductReferenceTable.productReferenceTable
        val prodRefRequest = ProductReferenceTable.productReferenceTable.returning(prodRefTable)
        val productReferencesToAdd = req.products.map{f =>
          (0, f.id)
        }
        val prodRefRequestData = prodRefRequest ++= productReferencesToAdd
        IO.fromFuture(IO(Setup.db.run(prodRefRequestData))).map{_ =>
          StoreOrderRequestWithId(a._1, req)
        }
      }
    }
  }
}

object GetStoreOrderRequestHelper {
  
  def getStoreOrderWithEmail(e: Email, ge:com.gardenShare.gardenshare.Storage.Relational.StoreOrderRequestTable.StoreOrderRequestTable => Rep[String])(implicit cs: ContextShift[IO], g: GetProductById[IO], par: ParseZoneDateTime): IO[List[StoreOrderRequestWithId]] = {
      val query = for {
        re <- StoreOrderRequestTable.storeOrderRequests if ge(re) === e.underlying
      } yield re
      IO.fromFuture(IO(Setup.db.run(query.result))).map(_.map{f =>
        val productReferenceQuery = for {
          pre <- ProductReferenceTable.productReferenceTable if pre.productReferenceTableId === f._1
        } yield pre
        IO.fromFuture(IO(Setup.db.run(productReferenceQuery.result))).flatMap{abb =>
          abb.map{a =>
            g.get(a._2)
          }
            .parSequence
            .map(_.collect{
              case Some(a) => a
            }).map{pd =>
              par.parseZoneDateTime(f._4).map{zdt =>
                StoreOrderRequestWithId(f._1, StoreOrderRequest(Email(f._2), Email(f._3), pd.toList, zdt))
              }
            }
        }
      })
        .map(_.parSequence)
        .flatten
        .map(_.toList)
        .map(_.collect{
          case Right(a) => a
        })
    }
}
import GetStoreOrderRequestHelper._

abstract class GetStoreOrderRequestsWithSellerEmail[F[_]] {
  def getWithEmail(e: Email)(implicit cs: ContextShift[F]): F[List[StoreOrderRequestWithId]]
}

object GetStoreOrderRequestsWithSellerEmail {
  implicit def createIOGetStoreOrderRequestsWithSellerEmail(implicit g: GetProductById[IO]) = new GetStoreOrderRequestsWithSellerEmail[IO] {
    def getWithEmail(e: Email)(implicit cs: ContextShift[IO]): IO[List[StoreOrderRequestWithId]] = getStoreOrderWithEmail(e, (x: StoreOrderRequestTable.StoreOrderRequestTable) => x.sellerEmail)
  }
}

abstract class GetStoreOrderRequestsWithBuyerEmail[F[_]] {
  def getWithEmail(e: Email)(implicit cs: ContextShift[F]): F[List[StoreOrderRequestWithId]]
}

object GetStoreOrderRequestsWithBuyerEmail {
  implicit def createGetStoreOrderRequestsWithBuyerEmailIO = new GetStoreOrderRequestsWithBuyerEmail[IO] {
    def getWithEmail(e: Email)(implicit cs: ContextShift[IO]): IO[List[StoreOrderRequestWithId]] = getStoreOrderWithEmail(e, (x: StoreOrderRequestTable.StoreOrderRequestTable) => x.buyerEmail)
  }
}
