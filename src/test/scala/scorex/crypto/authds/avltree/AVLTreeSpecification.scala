package scorex.crypto.authds.avltree

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.PropSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import scorex.crypto.authds.TwoPartyTests
import scorex.crypto.hash.Sha256
import scorex.utils.{ByteArray, Random}

import scala.util.Success

class AVLTreeSpecification extends PropSpec with GeneratorDrivenPropertyChecks with TwoPartyTests {

  val KL = 26
  val VL = 8

  property("lookup") {
    val tree = new AVLTree(KL)
    var digest = tree.rootHash()

    forAll(kvGen) { case (aKeyNN, aValue) =>
      val aKey = Random.randomBytes(KL)

      require(aKey.length == KL)
      digest shouldEqual tree.rootHash()

      tree.lookup(aKey).verifyLookup(digest, existence = false).get shouldEqual digest
      tree.lookup(aKey).verifyLookup(digest, existence = true) shouldBe None

      digest shouldEqual tree.rootHash()
      val proof = tree.modify(aKey, replaceLong(aValue))
      digest = proof.verify(digest, replaceLong(aValue)).get

      val l = tree.lookup(aKey)
      val parsed = AVLModifyProof.parseBytes(l.bytes)(KL, 32).get


      l.keyFound shouldBe true
      l.verifyLookup(digest, existence = true).get shouldEqual digest
      l.verifyLookup(digest, existence = false) shouldBe None
    }
  }

  property("infinities") {
    val tree = new AVLTree(KL)
    forAll(kvGen) { case (aKey, aValue) =>
      require(ByteArray.compare(aKey, tree.NegativeInfinity._1) > 0)
      require(ByteArray.compare(aKey, tree.PositiveInfinity._1) < 0)
    }
  }



  property("stream") {
    val wt = new AVLTree(KL)
    var digest = wt.rootHash()
    forAll(kvGen) { case (aKey, aValue) =>
      digest shouldEqual wt.rootHash()
      val proof = wt.modify(aKey, replaceLong(aValue))
      digest = proof.verify(digest, replaceLong(aValue)).get
    }
  }

  property("insert") {
    val wt = new AVLTree(KL)
    forAll(kvGen) { case (aKey, aValue) =>
      val digest = wt.rootHash()
      val proof = wt.modify(aKey, rewrite(aValue))
      proof.verify(digest, rewrite(aValue)).get shouldEqual wt.rootHash()
    }
  }


  property("update") {
    val wt = new AVLTree(KL)
    forAll(genBoundedBytes(KL, KL), genBoundedBytes(VL, VL), genBoundedBytes(VL, VL)) {
      (key: Array[Byte], value: Array[Byte], value2: Array[Byte]) =>
        whenever(!(value sameElements value2)) {
          val digest1 = wt.rootHash()
          val proof = wt.modify(key, replaceLong(value))
          proof.verify(digest1, replaceLong(value)).get shouldEqual wt.rootHash()

          val digest2 = wt.rootHash()
          val updateProof = wt.modify(key, replaceLong(value2))
          updateProof.verify(digest2, replaceLong(value2)).get shouldEqual wt.rootHash()
        }
    }
  }

  property("AVLModifyProof serialization") {
    val wt = new AVLTree(KL)

    genElements(100, 1, 26).foreach(e => wt.modify(e, replaceLong(e)))

    var digest = wt.rootHash()
    forAll(kvGen) { case (aKey, aValue) =>
      whenever(aKey.length == KL && aValue.length == VL) {
        digest shouldEqual wt.rootHash()
        val proof = wt.modify(aKey, replaceLong(aValue))

        digest = proof.verify(digest, replaceLong(aValue)).get
        val parsed = AVLModifyProof.parseBytes(proof.bytes)(KL, 32).get

        parsed.key shouldEqual proof.key
        parsed.proofSeq.indices foreach { i =>
          parsed.proofSeq(i).bytes shouldEqual proof.proofSeq(i).bytes
        }
        parsed.bytes shouldEqual proof.bytes

        val value2 = Sha256(aValue)
        val digest2 = wt.rootHash()
        val uProof = wt.modify(aKey, replaceLong(value2))
        digest = uProof.verify(digest2, replaceLong(value2)).get
        uProof.keyFound shouldBe true

        val uParsed = AVLModifyProof.parseBytes(uProof.bytes)(KL, 32).get
        uParsed.bytes shouldEqual uProof.bytes

      }
    }
  }

  def rewrite(value: AVLValue): UpdateFunction = {
    oldOpt: Option[AVLValue] => Success(value)
  }

  def kvGen: Gen[(Array[Byte], Array[Byte])] = for {
    key <- Gen.listOfN(KL, Arbitrary.arbitrary[Byte]).map(_.toArray) suchThat
      (k => !(k sameElements Array.fill(KL)(-1: Byte)) && !(k sameElements Array.fill(KL)(0: Byte)) && k.length == KL)
    value <- Gen.listOfN(VL, Arbitrary.arbitrary[Byte]).map(_.toArray)
  } yield (key, value)


}
