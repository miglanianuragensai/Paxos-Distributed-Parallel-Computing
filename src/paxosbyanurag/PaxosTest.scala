/**
  * Authors: anurag & thomas on 12/22/15.
  *
  * This App performs simple tests of the simple paxos algorithm implemented in paxos.scala in the same package.
  *
  * Although this is a test, it is written as a normal App in src/main.
  * One manually checks that the output complies with the requirements.
  *
  */

package paxosbyanurag

import java.io.{File, FileWriter, BufferedWriter}
import java.util.concurrent.TimeoutException
import akka.actor.{ActorRef, Actor, ActorSystem, Props}
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import akka.pattern.ask
import akka.{actor, dispatch}
import scala.util.Random

object PaxosTest extends App {

  val system = ActorSystem("mySystem")

  //Each actor will play every roles, we will create 5 actors in this, it can also be
  //extended, and as an input to the node class we will send the majority number as
  //out of 5 actors 3 will be majority, to have a consensus on the majority
  val actorA = system.actorOf(Props(new Node(3)), name = "actora")
  val actorB = system.actorOf(Props(new Node(3)), name = "actorb")
  val actorC = system.actorOf(Props(new Node(3)), name = "actorc")
  val actorD = system.actorOf(Props(new Node(3)), name = "actord")
  val actorE = system.actorOf(Props(new Node(3)), name = "actore")

  implicit val timeout = Timeout(20 seconds)
  // var folder with the persistent memory of the synod.
  val file = new File("var")

  /* Testing an execution of the synod multiple rounds with the testing function.
   * An election is made by randomly choosing a Node. If the round timesOut, then a new one randomly is chosen as leader.
   * testing should be implementing a Round, returning 0 for success, !=0 for failures.
   */
  def testExec(testing: ActorRef => Int) {

    var i: Int = 1
    while (i != 0) {

      val list = Random.shuffle(List('A', 'B', 'C', 'D', 'E'))

      list(1) match {
        case 'A' => i=testing(actorA)
        case 'B' => i=testing(actorB)
        case 'C' => i=testing(actorC)
        case 'D' => i=testing(actorD)
        case 'E' => i=testing(actorE)
      }
    }
  }

  // A testing function for testExec. Client requests a string law and awaits the result.
  def myRound(whichactor: ActorRef): Int = {
    val Round = whichactor ? ClientRequest(law)
    try {
      val RoundRes = Await.result(Round, 10 seconds)

      if (!RoundRes.toString.isEmpty) {
        println("Round successful: returned value is " + RoundRes)
        return 0
      }
      else {
        println("Round unsuccessful: electing new leader")
        return 1
      }
    } catch {
      case e: TimeoutException => return 2
    }
  }

  /* Actual testing begins:
   * The first test will do the following: starting anew, a client will request a law. Then a second one requests another law.
   * The first execution should learn the value and put it in the ledger. The second one should just return the value in the ledger.
   */

  // Initially, start with empty var directory
  if (!file.exists) {
    file.mkdir()
  }
  else {
    file.listFiles.map(_.delete)
  }

  // Then execute the request for law
  var law = "Superbe LAW"
  testExec(myRound)
  law = "Great MAN"
  testExec(myRound)


  /* Second test:
   * Two folds: first, some Nodes start with a promise on a higher number proposal, so the leader has to update its number.
   * Secondly, we wait until everyone finishes to send their messages, and then erase the learned value from the ledgers.
   * The memory of the other ledgers are kept alive: promises, last votes and proposal number.
   * On the second request, the synod algorithm will run again, but still should return the same value from the first request.
   */

  // Initially, start with empty var directory
  if (!file.exists) {
    file.mkdir()
  }
  else {
    file.listFiles.map(_.delete)
  }

  // Then make as if actorD has proposed with number 3
  var tmpFile = new File("var/actorcpromise")
  var bw = new BufferedWriter(new FileWriter(tmpFile))
  bw.write("actord:3")
  bw.close()
  tmpFile = new File("var/actordpromise")
  bw = new BufferedWriter(new FileWriter(tmpFile))
  bw.write("actord:3")
  bw.close()
  tmpFile = new File("var/actorepromise")
  bw = new BufferedWriter(new FileWriter(tmpFile))
  bw.write("actord:3")
  bw.close()

  // Execute with law
  testExec(myRound)
  // Delete the decrees learned and try again
  Thread.sleep(5000)
  println("Deleting Memory of Decrees")
  file.listFiles.filter(_.getName.contains("decrees")).map(_.delete)
  law = "Not my law"
  testExec(myRound)


  // shutdown the system, or write more tests.
  system.shutdown
}