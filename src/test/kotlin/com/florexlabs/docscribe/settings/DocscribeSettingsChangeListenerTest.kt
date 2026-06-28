package com.florexlabs.docscribe.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DocscribeSettingsChangeListenerTest : BasePlatformTestCase() {
    fun testTopicIsPublishable() {
        val publisher =
            ApplicationManager
                .getApplication()
                .messageBus
                .syncPublisher(DocscribeSettingsChangeListener.TOPIC)
        assertNotNull(publisher)
    }

    fun testApplyPublishesSettingsChanged() {
        val configurable = DocscribeSettingsConfigurable()
        configurable.createComponent()

        var called = false
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(
            DocscribeSettingsChangeListener.TOPIC,
            object : DocscribeSettingsChangeListener {
                override fun settingsChanged() {
                    called = true
                }
            },
        )

        configurable.omitBoilerplateCheckBox.isSelected = true
        configurable.apply()

        assertTrue(called)
    }

    fun testApplyPublishesAfterHideCommentsToggle() {
        val configurable = DocscribeSettingsConfigurable()
        configurable.createComponent()

        var called = false
        val connection = ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
        connection.subscribe(
            DocscribeSettingsChangeListener.TOPIC,
            object : DocscribeSettingsChangeListener {
                override fun settingsChanged() {
                    called = true
                }
            },
        )

        configurable.hideCommentsCheckBox.isSelected = true
        configurable.apply()

        assertTrue(called)
    }
}
