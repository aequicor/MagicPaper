package io.aequicor.magicpaper.machine.render

/**
 * Renders a machine as a Mermaid state diagram.
 *
 * Every edge is a transition the reducer actually made over a representative, so a guarded
 * transition appears as the several edges its guard really produces. A lambda-based library cannot
 * do this: its exporter either omits conditional transitions or calls user lambdas with a fake
 * event to find out. Here the condition shows itself, because the position it depends on is named.
 *
 * Refused cells are left out by default — a diagram of everything a machine will not do is not a
 * diagram of the machine.
 */
fun MachineProjection.toMermaid(showRefusals: Boolean = false): String = buildString {
    appendLine("stateDiagram-v2")
    appendLine("    %% ${id.name}")
    phases.firstOrNull()?.let { appendLine("    [*] --> ${node(it)}") }
    cells.filter { showRefusals || !it.rejected }.forEach { cell ->
        val carried = cell.effects.filterNot { it.name == "Reject" }
        val label = cell.input.name + if (carried.isEmpty()) "" else " / " + carried.joinToString("+") { it.name }
        // A refused cell moved nothing, so its edge, when asked for, loops back where it started.
        val target = node(cell.to ?: cell.from)
        appendLine("    ${node(cell.from)} --> $target : $label" + if (cell.rejected) " ✗" else "")
    }
    unknownPhases.forEach { appendLine("    ${node(it)} : неизвестный исход") }
}

/** A markdown page: the diagram, then the acceptance matrix the api declares. */
fun MachineProjection.toMarkdown(): String = buildString {
    appendLine("## ${id.name}")
    appendLine()
    appendLine("```mermaid")
    append(toMermaid())
    appendLine("```")
    appendLine()
    appendLine("| позиция | " + inputs.joinToString(" | ") { it.id.name } + " |")
    appendLine("| --- |" + inputs.joinToString("") { " --- |" })
    phases.forEach { phase ->
        val row = inputs.joinToString(" | ") { spec ->
            val cell = cells.first { it.from == phase && it.input == spec.id }
            if (cell.rejected) "·" else cell.to?.name ?: "?"
        }
        val mark = if (phase in unknownPhases) " ⚠" else ""
        appendLine("| ${phase.name}$mark | $row |")
    }
}

private fun node(phase: io.aequicor.magicpaper.machine.PhaseId) =
    phase.name.replace('-', '_').replace(' ', '_')
