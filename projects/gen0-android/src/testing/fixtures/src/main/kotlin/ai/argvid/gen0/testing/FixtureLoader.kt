package ai.argvid.gen0.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object FixtureLoader {
    private const val ScenarioVersion = "gen0.parity/1.0"

    private val sessionPaths = listOf(
        "parity/session/motion-pause.json",
        "parity/session/stop-from-running.json",
    )

    private val sessionStates = setOf(
        "idle",
        "preflight",
        "calibrating",
        "running",
        "paused_privacy",
        "paused_interruption",
        "paused_motion",
        "degraded_thermal",
        "degraded_offline",
        "degraded_quota",
        "ending",
        "ended",
    )

    private val sessionEvents = setOf(
        "begin_preflight",
        "preflight_passed",
        "calibration_passed",
        "motion_started",
        "motion_settled",
        "rescue_buffer_warmed",
        "resume_requested",
        "interrupted",
        "stop_requested",
        "end_completed",
    )

    private val transitionErrors = setOf(
        "buffer_not_warm",
        "illegal_transition",
    )

    fun sessionScenarios(): List<ParityScenario> = sortedUnique(sessionPaths.map(::load))

    fun load(path: String): ParityScenario {
        val content = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
            "Fixture not found: $path"
        }.bufferedReader().use { it.readText() }
        return parseSession(content)
    }

    internal fun parseSession(content: String): ParityScenario {
        val root = Json.parseToJsonElement(content).jsonObject
        root.requireKeys(
            required = setOf("scenario_version", "id", "initial_state", "event", "expected"),
        )

        val scenarioVersion = root.requiredString("scenario_version")
        require(scenarioVersion == ScenarioVersion) {
            "Unsupported scenario_version: $scenarioVersion"
        }

        val id = root.requiredString("id")
        require(id.isNotBlank()) { "Fixture id must not be blank" }

        val initialState = root.requiredString("initial_state")
        require(initialState in sessionStates) { "Unknown initial_state: $initialState" }

        val event = root.requiredString("event")
        require(event in sessionEvents) { "Unknown event: $event" }

        val expectedObject = root.getValue("expected").jsonObject
        expectedObject.requireKeys(
            required = setOf("accepted"),
            optional = setOf("state", "error"),
        )
        val accepted = expectedObject.requiredBoolean("accepted")
        val state = expectedObject.optionalString("state")
        val error = expectedObject.optionalString("error")

        if (accepted) {
            require(state in sessionStates) { "Accepted transition requires a known state" }
            require(error == null) { "Accepted transition cannot contain error" }
        } else {
            require(state == null) { "Rejected transition cannot contain state" }
            require(error in transitionErrors) { "Rejected transition requires a known error" }
        }

        return ParityScenario(
            scenarioVersion = scenarioVersion,
            id = id,
            initialState = initialState,
            event = event,
            expected = ExpectedTransition(accepted, state, error),
        )
    }

    internal fun sortedUnique(scenarios: List<ParityScenario>): List<ParityScenario> {
        val duplicateIds = scenarios.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate fixture id: ${duplicateIds.sorted().joinToString()}" }
        return scenarios.sortedBy { it.id }
    }

    private fun JsonObject.requireKeys(
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val missing = required - keys
        val unknown = keys - required - optional
        require(missing.isEmpty()) { "Missing keys: ${missing.sorted().joinToString()}" }
        require(unknown.isEmpty()) { "Unknown keys: ${unknown.sorted().joinToString()}" }
    }

    private fun JsonObject.requiredString(key: String): String {
        val primitive = getValue(key).jsonPrimitive
        require(primitive.isString) { "$key must be a string" }
        return primitive.content
    }

    private fun JsonObject.optionalString(key: String): String? {
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a string")
        require(primitive.isString) { "$key must be a string" }
        return primitive.content
    }

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        getValue(key).jsonPrimitive.booleanOrNull
            ?: throw IllegalArgumentException("$key must be a boolean")
}
