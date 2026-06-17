package com.cliagent.llm.model

object PromptTemplates {

    fun buildSystemMessage(strategy: ReasoningStrategy): ChatMessage {
        return when (strategy) {
            ReasoningStrategy.DIRECT -> ChatMessage(
                role = "system",
                content = "You are a helpful AI assistant. Answer the question directly."
            )
            ReasoningStrategy.STEP_BY_STEP -> ChatMessage(
                role = "system",
                content = """
                    You are a helpful AI assistant.
                    Solve the problem step by step:
                    1. Analyze the problem
                    2. Identify key components
                    3. Solve each step
                    4. Provide the final answer
                    Show your reasoning at each step.
                """.trimIndent()
            )
            ReasoningStrategy.META_PROMPT -> ChatMessage(
                role = "system",
                content = """
                    You are a helpful AI assistant.
                    First, create an optimal prompt for solving the given problem.
                    Then, use that prompt to solve the problem.
                    Format your response as:
                    ===PROMPT===
                    [your optimized prompt here]
                    ===SOLUTION===
                    [your solution using the prompt]
                """.trimIndent()
            )
            ReasoningStrategy.EXPERT_GROUP -> ChatMessage(
                role = "system",
                content = """
                    You are a panel of three experts analyzing the problem:
                    - **Analyst**: Identifies the core problem and constraints
                    - **Engineer**: Proposes a practical solution
                    - **Critic**: Reviews the solution for flaws and improvements
                    Each expert provides their analysis, then you give a final synthesis.
                    Format:
                    ### Analyst
                    [analysis]
                    ### Engineer
                    [solution]
                    ### Critic
                    [review]
                    ### Final Answer
                    [synthesis]
                """.trimIndent()
            )
        }
    }
}
