package spinal.debugger

import spinal.core._
import spinal.lib._

import scala.collection.mutable.ArrayBuffer


object LogicAnalyser {

}

class LogicAnalyserParameter {
  var memAddressWidth = 8

  val dataList = ArrayBuffer[Data]()
  def probe(that: Data) {
    dataList += that
  }
}

class LogicAnalyserConfig(p: LogicAnalyserParameter) extends Bundle{
  val trigger = new Bundle{
    val delay = UInt(32 bit)
  }
  val logger = new Bundle{
    val samplesLeftAfterTrigger = UInt(p.memAddressWidth bit)
  }

  override def clone() : this.type = new LogicAnalyserConfig(p).asInstanceOf[this.type]
}

class LogicAnalyser(p: LogicAnalyserParameter) extends Component {
  val fragmentWidth = 8
  val io = new Bundle {
    val slavePort = slave Flow Fragment(Bits(fragmentWidth bit))
    val masterPort = master Handshake Fragment(Bits(fragmentWidth bit))
  }



  val waitTrigger = io.slavePort filterHeader(0x01) toRegOf(Bool) init(False)
  val userTrigger = io.slavePort eventOn(0x02)
  val configs = io.slavePort filterHeader(0x0F) toRegOf(new LogicAnalyserConfig(p),false)

  val trigger = new Area {
    val event = CounterFreeRun(1000) === U(999) || userTrigger
    when(event){
      waitTrigger := False
    }
  }

  val probe = Cat(p.dataList.map(_.pull))

  val logger = new LogicAnalyserLogger(p,probe)
  logger.io.configs := configs
  logger.io.trigger := DelayEvent(trigger.event,configs.trigger.delay) && waitTrigger
  logger.io.probe := probe

  io.masterPort << logger.io.log.toFragmentBits(fragmentWidth).insertHeader(0xAA)
}


object LogicAnalyserLoggerState extends SpinalEnum {
  val sWaitTrigger, sSample, sPush, Nil = Value
}

class LogicAnalyserLogger(p: LogicAnalyserParameter, probeType: Bits) extends Component {

  import LogicAnalyserLoggerState._

  val io = new Bundle {
    val configs = in (new LogicAnalyserConfig(p))

    val trigger = in Bool
    val probe = in cloneOf(probeType)

    val log = master Handshake Fragment(probe)
  }

  val mem = Mem(probeType, 1 << p.memAddressWidth)
  val memWriteAddress = Reg(mem.addressType) randBoot
  val memReadAddress = Reg(mem.addressType)




  val state = RegInit(sWaitTrigger)
  val pushCounter = Reg(mem.addressType)


  val sampler = new Area {
    val preEnable = False
    val postEnable = False
    val counter = Reg(mem.addressType)

    when(postEnable){
      counter := counter - 1
    } otherwise{
      counter := io.configs.logger.samplesLeftAfterTrigger
    }

    when(preEnable || postEnable) {
      mem(memWriteAddress) := io.probe
      memWriteAddress := memWriteAddress + 1
    }

    val done = counter === 0
  }


  val memReadCmd = Handshake(mem.addressType)
  val memReadCmdIsLast = False

  memReadCmd.valid := False
  memReadCmd.data := memReadAddress


  when(state === sWaitTrigger) {
    sampler.preEnable := True
    when(io.trigger) {
      state := sSample
      memReadAddress := memWriteAddress + io.configs.logger.samplesLeftAfterTrigger + 2
    }
  }
  when(state === sSample) {
    sampler.postEnable := True
    when(sampler.done) {
      state := sPush
      pushCounter := U(0)
    }
  }
  when(state === sPush) {
    memReadCmd.valid := True
    when(memReadCmd.ready) {
      memReadAddress := memReadAddress + U(1)
      pushCounter := pushCounter + U(1)
    }
    when(pushCounter === U((1 << pushCounter.getWidth) - 1)) {
      memReadCmdIsLast := True
      when(memReadCmd.ready) {
        state := sWaitTrigger
      }
    }
  }


  val memReadPort = mem.handshakeReadSync(memReadCmd, memReadCmdIsLast)
  io.log.translateFrom(memReadPort)((to, from) => {
    to.last := from.linked
    to.fragment := from.value
  })

}