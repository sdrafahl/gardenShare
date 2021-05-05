package com.gardenShare.gardenshare

import cats.effect.IO
import org.http4s._
import org.http4s.implicits._
import munit.CatsEffectSuite
import com.gardenShare.gardenshare.Email
import com.gardenShare.gardenshare.Password
import fs2.text
import io.circe.fs2._
import io.circe.generic.auto._, io.circe.syntax._
import utest.TestSuite
import utest.test
import utest.Tests
import com.gardenShare.gardenshare.UserInfo
import com.gardenShare.gardenshare.Sellers
import com.gardenShare.gardenshare.DeleteStore
import com.gardenShare.gardenshare.GetStore
import com.gardenShare.gardenshare.Address
import com.gardenShare.gardenshare.IA
import com.gardenShare.gardenshare.Store._
import com.typesafe.config.ConfigFactory
import java.net.URL
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import eu.timepit.refined.api.RefType
import scala.util.Try
import PaymentID._
import ParsingDecodingImplicits._

object StoreOrderRequestsTest extends TestSuite {

  lazy implicit val config = ConfigFactory.load()
  lazy implicit val dbClient = PostGresSetup.createPostgresClient
  val executionStuff = ConcurrencyHelper.createConcurrencyValues(2)
  implicit val cs = executionStuff._3

  val tests = Tests {
    test("Store Order Request") {
      val testBuyerEmail = Email("testbuyer@gmail.com")
      val testpassword = "testPass12123$"
      val testSellerEmail = Email("testseller@gmail.com")
      val testRefreshURL = new URL("http://localhost:3000/")
      val testReturnURL = new URL("http://localhost:3000/")
      
      test("Can create a order, and query for the order, then accept the request, and then deny the request") {
        val accountID = UserTestsHelper.getTestStripeAccount

        UserTestsHelper.deleteUserAdmin(testBuyerEmail)
        UserTestsHelper.deleteUserAdmin(testSellerEmail)
        UserTestsHelper.deleteAllStoreOrdersForSeller[IO](testSellerEmail).unsafeRunSync()
        UserTestsHelper.adminCreateUser(testBuyerEmail, testpassword)
        UserTestsHelper.adminCreateUser(testSellerEmail, testpassword)
        UserTestsHelper.insertSlickEmailReference(testSellerEmail, accountID).unsafeRunSync()
        val r = UserTestsHelper.authUser(testSellerEmail, testpassword)
        
        val jwtTokenOfTheSeller = r.auth.get.jwt
        val jwtTokenOfTheBuyer = UserTestsHelper.authUser(testBuyerEmail, testpassword).auth.get.jwt

        val address = Address("500 hickman Rd", "Waukee", "50263", IA)
        UserTestsHelper.verifyUserAsSeller(jwtTokenOfTheSeller, address)
        UserTestsHelper.addProductToStore("BrownOysterMushrooms", jwtTokenOfTheSeller, Amount(100, USD))
        val products = UserTestsHelper.getProductsFromStore(jwtTokenOfTheSeller)
        val productsWithQuantity = products.listOfProduce.map(prd => ProductAndQuantity(prd, 1))
        val storeOrderRequestBody = StoreOrderRequestBody(productsWithQuantity)
        
        val responseFromCreatingArequest = UserTestsHelper.createStoreOrderRequest(jwtTokenOfTheBuyer, testSellerEmail, storeOrderRequestBody)
        
        val now = GetCurrentDate[IO]().get.unsafeRunSync()
        val yesterday = now.minusDays(1)
        val orders = UserTestsHelper.getListOfOrdersFromSellers(jwtTokenOfTheSeller, yesterday, now)
        val orderID = orders.body.head.id
        val orderStatusAfterCreating = UserTestsHelper.getOrderStatus(orderID)
        val expectedOrderStatusAfterCreating = StoreOrderRequestStatusBody(RequestToBeDetermined)
        assert(orderStatusAfterCreating.equals(expectedOrderStatusAfterCreating))
        assert(orders.body.head.storeOrderRequest.buyer.equals(testBuyerEmail))
        assert(responseFromCreatingArequest.storeOrderRequest.seller.equals(testSellerEmail))
        assert(responseFromCreatingArequest.storeOrderRequest.buyer.equals(testBuyerEmail))
        
        UserTestsHelper.acceptOrder(orderID, jwtTokenOfTheSeller)

        val statusAfterAcceptingTheOrder = UserTestsHelper.getOrderStatus(orderID)
        val exceptedOrderStatusAfterAccepting = StoreOrderRequestStatusBody(AcceptedRequest)
        
        assert(statusAfterAcceptingTheOrder.equals(exceptedOrderStatusAfterAccepting))
        val initiatePaymentResponse = UserTestsHelper.initiatePayment(jwtTokenOfTheBuyer, orderID, testBuyerEmail, Card)
        
        val secretId = UserTestsHelper.getPaymentIntentID(orderID).get       
        val intentId = secretId.parsePublicKey
        val testCard = CreditCard("4242424242424242", 1, 2030, 333)
        
        UserTestsHelper.confirmOrder(intentId, testCard)
        val intent = UserTestsHelper.getStatusOfIntent(intentId)
        assert(intent.getStatus() == "succeeded")

        val verificationResult = UserTestsHelper.verifyPayment(orderID, jwtTokenOfTheBuyer)
        assert(verificationResult.status == PaymentComplete)

      }
    }    
  }
}

