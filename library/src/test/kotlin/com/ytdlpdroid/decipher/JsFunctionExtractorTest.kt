package com.ytdlpdroid.decipher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsFunctionExtractorTest {

    @Test
    fun `extracts assignment-style function`() {
        val js = "var foo=function(a){return a.reverse()};"
        val result = JsFunctionExtractor.extractFunctionBody(js, "foo")
        assertNotNull(result)
        assertTrue(result!!.contains("return a.reverse()"))
    }

    @Test
    fun `extracts declaration-style function`() {
        val js = "function bar(a,b){return a+b;}"
        val result = JsFunctionExtractor.extractFunctionBody(js, "bar")
        assertNotNull(result)
        assertTrue(result!!.startsWith("function bar"))
    }

    @Test
    fun `returns null for nonexistent function`() {
        val js = "var x=1;"
        assertNull(JsFunctionExtractor.extractFunctionBody(js, "missing"))
    }

    @Test
    fun `handles nested braces correctly`() {
        val js = "function nested(a){if(a){return{x:1}}return{}}"
        val result = JsFunctionExtractor.extractFunctionBody(js, "nested")
        assertNotNull(result)
        // Must include all braces — last char is '}'
        assertEquals('}', result!!.last())
    }

    @Test
    fun `handles braces inside strings`() {
        val js = """function strBrace(a){var s="{not a brace}";return s+a}"""
        val result = JsFunctionExtractor.extractFunctionBody(js, "strBrace")
        assertNotNull(result)
        assertTrue(result!!.contains("not a brace"))
    }

    @Test
    fun `extracts object literal`() {
        val js = "var Ob={op:function(a,b){a.splice(0,b)},rv:function(a){a.reverse()}};"
        val result = JsFunctionExtractor.extractObjectLiteral(js, "Ob")
        assertNotNull(result)
        assertTrue(result!!.startsWith("{"))
        assertTrue(result.endsWith("}"))
        assertTrue(result.contains("splice"))
    }

    @Test
    fun `returns null for missing object`() {
        assertNull(JsFunctionExtractor.extractObjectLiteral("var x=1;", "Missing"))
    }
}
