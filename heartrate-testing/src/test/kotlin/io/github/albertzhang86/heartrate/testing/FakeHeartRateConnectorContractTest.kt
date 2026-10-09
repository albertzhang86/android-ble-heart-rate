package io.github.albertzhang86.heartrate.testing

import io.github.albertzhang86.heartrate.HeartRateMeasurement

class FakeHeartRateConnectorContractTest : HeartRateConnectorContractTest() {
    override fun createFixture() = object : Fixture {
        override val connector = FakeHeartRateConnector()
        override val device = FakeHeartRateConnector.DEVICE
        override fun emit(measurement: HeartRateMeasurement) = connector.emit(measurement)
    }
}
