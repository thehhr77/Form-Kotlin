package com.thehhr.form.nativeapp.vault

interface VaultBatchJournal {
    suspend fun prepare(plan: VaultBatchPlan)
    suspend fun mark(status: String)
    val location: String
}

object VaultBatchExecutor {
    suspend fun execute(store: VaultStore, plan: VaultBatchPlan, journal: VaultBatchJournal, checkWorkout: suspend () -> Unit, invalidate: () -> Unit) {
        require(!plan.consumed) { "This operation was already started. Reload and explicitly create a new preview; it cannot be retried." }
        SettingsDataPlanner.validate(plan)
        suspend fun compare(expected: Map<String, String>) {
            expected.forEach { (file, bytes) ->
                val result = store.read(file)
                if (result.isError || result.content != bytes) throw VaultConflictException("$file differs from the captured/expected bytes. Reload and create a new preview; no automatic rebase.")
            }
        }
        checkWorkout()
        compare(plan.expected)
        plan.consumed = true
        try {
            journal.prepare(plan)
            if (plan.desired.isEmpty()) {
                journal.mark("complete")
                return
            }
            checkWorkout()
            compare(plan.expected)
            journal.mark("started")
            val expected = plan.expected.toMutableMap()
            for ((file, content) in plan.desired) {
                checkWorkout()
                compare(expected)
                store.write(file, content)
                expected[file] = content
                compare(expected)
                journal.mark("verified-$file")
            }
            checkWorkout()
            compare(expected)
            journal.mark("complete")
        } catch (error: Exception) {
            invalidate()
            runCatching { journal.mark("partial-or-unverified") }
            throw VaultReloadRequiredException("Batch failed during preparation or writing. Files written before the failure are not rolled back. SAF cannot atomically update multiple files. Reload is required; this preview cannot be retried. Recoverable originals and planned outputs: ${journal.location}. Restore manually after comparing files; no automatic rollback was attempted.", error)
        }
    }
}
