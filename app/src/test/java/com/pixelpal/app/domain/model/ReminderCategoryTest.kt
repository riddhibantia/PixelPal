package com.pixelpal.app.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderCategoryTest {

    @Test
    fun fromId_parsesAllKnownCategories() {
        ReminderCategory.entries.forEach { category ->
            assertEquals(category, ReminderCategory.fromId(category.id))
            assertEquals(category, ReminderCategory.fromId(category.id.lowercase()))
        }
    }

    @Test
    fun fromId_fallsBackToCustom_forUnknownNullOrBlank() {
        assertEquals(ReminderCategory.CUSTOM, ReminderCategory.fromId(null))
        assertEquals(ReminderCategory.CUSTOM, ReminderCategory.fromId(""))
        assertEquals(ReminderCategory.CUSTOM, ReminderCategory.fromId("NOT_A_CATEGORY"))
        // Legacy rows wrote "CUSTOM" explicitly and must keep working.
        assertEquals(ReminderCategory.CUSTOM, ReminderCategory.fromId("CUSTOM"))
    }

    @Test
    fun ids_areUniqueAndNonBlank() {
        val ids = ReminderCategory.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun everyCategoryHasDisplayNameAndSoundHint() {
        ReminderCategory.entries.forEach { category ->
            assertTrue(category.displayName.isNotBlank(), "${category.id} has no display name")
            // soundHint is a total function: accessing it must never throw.
            assertTrue(category.soundHint in ReminderSound.entries)
        }
    }

    @Test
    fun sound_fromId_fallsBackToDefault() {
        ReminderSound.entries.forEach { sound ->
            assertEquals(sound, ReminderSound.fromId(sound.id))
        }
        assertEquals(ReminderSound.DEFAULT, ReminderSound.fromId(null))
        assertEquals(ReminderSound.DEFAULT, ReminderSound.fromId("nonsense"))
    }

    @Test
    fun silent_isAnExplicitOption() {
        // Regression: "Silent" used to be stored as soundUri=null, which meant
        // "system default" — making silence impossible. NONE must exist and be
        // distinct from DEFAULT.
        assertTrue(ReminderSound.NONE in ReminderSound.entries)
        assertTrue(ReminderSound.NONE != ReminderSound.DEFAULT)
        assertEquals("NONE", ReminderSound.NONE.id)
    }

    @Test
    fun shopping_isSilentByDefault() {
        // Category-driven default: errands shouldn't blast an alarm.
        assertEquals(ReminderSound.NONE, ReminderCategory.SHOPPING.soundHint)
        assertEquals(ReminderSound.ALARM, ReminderCategory.MEDICINE.soundHint)
    }
}
