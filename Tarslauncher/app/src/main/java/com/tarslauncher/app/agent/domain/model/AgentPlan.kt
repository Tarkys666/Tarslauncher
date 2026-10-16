package com.tarslauncher.app.agent.domain.model

data class PlanStep(
    val index: Int,
    val title: String,
    val isCompleted: Boolean = false,
    val isInProgress: Boolean = false
)

data class AgentPlan(
    val goal: String,
    val steps: List<PlanStep> = emptyList()
) {
    fun markStepInProgress(stepIndex: Int): AgentPlan {
        return copy(
            steps = steps.map { step ->
                when {
                    step.index == stepIndex -> step.copy(isInProgress = true, isCompleted = false)
                    step.index < stepIndex -> step.copy(isCompleted = true, isInProgress = false)
                    else -> step.copy(isCompleted = false, isInProgress = false)
                }
            }
        )
    }

    fun markStepCompleted(stepIndex: Int): AgentPlan {
        return copy(
            steps = steps.map { step ->
                if (step.index <= stepIndex) {
                    step.copy(isCompleted = true, isInProgress = false)
                } else {
                    step
                }
            }
        )
    }

    fun markAllCompleted(): AgentPlan {
        return copy(
            steps = steps.map { it.copy(isCompleted = true, isInProgress = false) }
        )
    }

    fun replaceRemainingSteps(newStepTitles: List<String>): AgentPlan {
        val completedPrefix = steps.filter { it.isCompleted }
        val startIndex = completedPrefix.size + 1
        val newSteps = newStepTitles.mapIndexed { offset, title ->
            PlanStep(
                index = startIndex + offset,
                title = title,
                isCompleted = false,
                isInProgress = offset == 0
            )
        }
        return copy(steps = completedPrefix + newSteps)
    }
}
