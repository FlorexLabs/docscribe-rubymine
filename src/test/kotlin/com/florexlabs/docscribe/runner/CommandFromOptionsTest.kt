package com.florexlabs.docscribe.runner

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandFromOptionsTest {
    @Test
    fun safeStrategyReturnsSafeFix() {
        val options = RunOptions(projectDir = "/project", strategy = DocscribeStrategy.SAFE)
        assertEquals("safe_fix", DocscribeDaemon.commandFromOptions(options))
    }

    @Test
    fun aggressiveStrategyReturnsAggressiveFix() {
        val options = RunOptions(projectDir = "/project", strategy = DocscribeStrategy.AGGRESSIVE)
        assertEquals("aggressive_fix", DocscribeDaemon.commandFromOptions(options))
    }

    @Test
    fun checkStrategyReturnsCheck() {
        val options = RunOptions(projectDir = "/project", strategy = DocscribeStrategy.CHECK)
        assertEquals("check", DocscribeDaemon.commandFromOptions(options))
    }

    @Test
    fun updateTypesSubcommandReturnsUpdateTypes() {
        val options = RunOptions(projectDir = "/project", subcommand = "update_types")
        assertEquals("update_types", DocscribeDaemon.commandFromOptions(options))
    }

    @Test
    fun subcommandTakesPriorityOverStrategy() {
        val options = RunOptions(projectDir = "/project", subcommand = "update_types", strategy = DocscribeStrategy.SAFE)
        assertEquals("update_types", DocscribeDaemon.commandFromOptions(options))
    }
}
