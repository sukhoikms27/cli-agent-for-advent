# Задача 09. CLI: `/invariants` команды + wiring `InvariantGuard`

## Что
Slash-команды для управления инвариантами (по образцу `/profile`, день 12) + wiring декоратора в
REPL при флаге `--invariants` + обновление `/help`.

## Зависимости
07 (`ContextAwareAgent.getInvariants/addInvariant/removeInvariant/setInvariants`), 08 (`InvariantGuard`),
04 (`LlmInvariantChecker`), 01 (`Invariant`).

## Реализация
Правка `src/main/kotlin/com/cliagent/cli/ChatCommand.kt`.

### 9.1 Флаг `--invariants`
Новый option (рядом с `--auto-profile`):
```kotlin
private val invariantsEnabled by option(
    "--invariants/--no-invariants",
    envvar = "CLI_AGENT_INVARIANTS"
).flag(default = false)
```
Default **off** → без флага поведение = день 13 (нулевая регрессия).

### 9.2 Wiring декоратора (в `run()`, после создания `base` агента)
```kotlin
val base = ContextAwareAgent(...)

// День 14: декоратор инвариантов (opt-in). Judge — та же модель, тот же client.
val agent: Agent = if (invariantsEnabled) {
    val checker = LlmInvariantChecker(client, model)
    InvariantGuard(base, checker) { base.getInvariants() }
} else {
    base
}
```
`TaskOrchestrator` (day-13 авто-поток) держит ссылку на `base` (ему нужны task-state аксессоры);
guard оборачивает только путь `else` REPL (`agent.chat`). Уточнить: orchestrator тоже должен
ходить через guard — см. риск ниже; в MVP guard оборачивает `agent` для REPL-чата.

### 9.3 Команды `/invariants`
Новый handler `handleInvariants(input, agent)` (по образцу `handleProfile`):
```kotlin
private suspend fun handleInvariants(input: String, agent: Agent) {
    val parts = input.trim().split("\\s+".toRegex())
    if (parts.size < 2 || parts[1] == "show") {
        // /invariants  |  /invariants show
        val list = (agent as? ContextAwareAgent)?.getInvariants() ?: base.getInvariants()
        if (list.isEmpty()) AppTerminal.println("🔒 No project invariants. Use: /invariants add <category> <rule>")
        else { AppTerminal.println("🔒 Project invariants:"); list.forEach {
            AppTerminal.println("  [${it.category.name.lowercase()}] ${it.id}: ${it.rule}") } }
        return
    }
    when (parts[1]) {
        "add"    -> { /* /invariants add <category> <id> <rule-text>  →  base.addInvariant(...) */ }
        "remove" -> { /* /invariants remove <id>  →  removed = base.removeInvariant(id); ok/warn */ }
        "clear"  -> { /* /invariants clear  →  base.setInvariants([]) */ }
        else     -> AppTerminal.println("Unknown /invariants command: ${parts[1]}. Use: show, add, remove, clear")
    }
}
```
Синтаксис `add`: `/invariants add <STACK|BAN|ARCH|BUSINESS> <id> <rule text...>` —
`InvariantCategory.valueOf(parts[2].uppercase())`, id = `parts[3]`, rule = `parts.drop(4).joinToString(" ")`.

Dispatch: `input.startsWith("/invariants") -> handleInvariants(input, base)` (нужен `base`-тип
`ContextAwareAgent`, не `Agent`-guard — у guard нет аксессоров; провайдить `base`).

### 9.4 `/help`
Добавить секцию (рядом с `/profile`):
```
|  /invariants                — Show project invariants (hard rules the agent must not violate)
|  /invariants add <cat> <id> <text> — Add invariant (cat: STACK/BAN/ARCH/BUSINESS)
|  /invariants remove <id>    — Remove invariant by id
|  /invariants clear          — Clear all invariants
|  Note: --invariants enables runtime checking (request refusal + response retry). Without it,
|        invariants are listed in the prompt but not enforced in code.
```

## Проверка
- `cmd.parse(listOf("--invariants"))` → флаг установлен (clikt-тест, образец day-12).
- `/invariants add BAN no-compose "no Jetpack Compose"` → `base.getInvariants()` содержит правило.
- `/invariants show` → вывод с категорией/id/rule.
- `/invariants remove no-compose` → `ok removed` / `warn not found`.
- `/invariants` без аргументов без инвариантов → «No project invariants».
- Manual: с `--invariants` + инвариантом → запрос-нарушитель даёт `⛔` без LLM-ответа.

## Риски
- **Orchestrator vs guard:** `TaskOrchestrator` (day-13) дёргает `base.chat()` напрямую внутри
  стадий. В MVP guard проверяет только путь REPL `else` (`agent.chat`), НЕ stage-артефакты. Это
  осознанный scope: stage-агенты уже имеют свой validation (`ValidationStageAgent`). Если нужно
  проверять артефакты стадий — extension point (обернуть orchestrator), но это усложнит авто-флоу.
  Документировать в README как ограничение MVP.
- **Приведение `agent as? ContextAwareAgent`** в `handleInvariants` —guard реализует `Agent`, у
  него нет `getInvariants`. Решение: передавать `base` (ContextAwareAgent) в handler явно.
