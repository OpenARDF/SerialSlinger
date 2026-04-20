package com.openardf.serialslinger.model

enum class StartTimeDaysToRunChoice {
    PRESERVE,
    RESET,
}

data class StartTimeDaysToRunOption(
    val choice: StartTimeDaysToRunChoice,
    val label: String,
)

data class StartTimeDaysToRunPlan(
    val autoChoice: StartTimeDaysToRunChoice?,
    val options: List<StartTimeDaysToRunOption>,
)

object StartTimeDaysToRunPlanner {
    fun plan(currentDaysToRun: Int): StartTimeDaysToRunPlan {
        if (currentDaysToRun <= 1) {
            return StartTimeDaysToRunPlan(
                autoChoice = StartTimeDaysToRunChoice.RESET,
                options = emptyList(),
            )
        }
        return StartTimeDaysToRunPlan(
            autoChoice = null,
            options = listOf(
                StartTimeDaysToRunOption(
                    choice = StartTimeDaysToRunChoice.PRESERVE,
                    label = "Preserve current Days To Run ($currentDaysToRun)",
                ),
                StartTimeDaysToRunOption(
                    choice = StartTimeDaysToRunChoice.RESET,
                    label = "Reset Days To Run to 1",
                ),
            ),
        )
    }
}
