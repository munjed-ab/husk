package com.munjed.husk

import com.munjed.husk.helper.contactMatches
import com.munjed.husk.helper.normalizeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatchTest {

    @Test
    fun `matches by name regardless of case`() {
        assertTrue(contactMatches("Ahmad Nabil", "0790000000", "ahmad"))
        assertTrue(contactMatches("Ahmad Nabil", "0790000000", "NAB"))
        assertFalse(contactMatches("Ahmad Nabil", "0790000000", "zzz"))
    }

    @Test
    fun `matches by number ignoring formatting on either side`() {
        assertTrue(contactMatches("Mom", "+962 79 123 4567", "0791234567".drop(1)))
        assertTrue(contactMatches("Mom", "079-123-4567", "1234"))
        assertFalse(contactMatches("Mom", "0791234567", "9999"))
    }

    @Test
    fun `folds arabic spelling variants`() {
        assertEquals("احمد", "أحمد".normalizeName())
        assertEquals("فاطمه", "فاطمة".normalizeName())
        assertEquals("يحيي", "يحيى".normalizeName())
        assertEquals("محمد", "مُحَمَّد".normalizeName())
        assertTrue(contactMatches("أحمد", "0790000000", "احمد"))
        assertTrue(contactMatches("فاطمة", "0790000000", "فاطمه"))
    }

    @Test
    fun `digits never match letters`() {
        // typing a number looks for a number, never a T9 spelling of the name
        assertFalse(contactMatches("Abdulrahman", "0790000000", "626"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(contactMatches("Anyone", "0790000000", "   "))
    }
}
