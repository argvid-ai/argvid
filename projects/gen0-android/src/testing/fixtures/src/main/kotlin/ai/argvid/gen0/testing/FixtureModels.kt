package ai.argvid.gen0.testing

data class ParityScenario(
    val scenarioVersion: String,
    val id: String,
    val initialState: String,
    val event: String,
    val expected: ExpectedTransition,
)

data class ExpectedTransition(
    val accepted: Boolean,
    val state: String?,
    val error: String?,
)
