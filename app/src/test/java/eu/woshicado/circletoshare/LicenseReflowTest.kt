package eu.woshicado.circletoshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** How a license text is laid out for a phone (see [reflow]). */
class LicenseReflowTest {

    private fun raw(name: String) = File("src/main/res/raw/$name").readText()

    @Test
    fun aParagraph_becomesOneLine() {
        assertEquals(
            "\"License\" shall mean the terms and conditions for use. It is so.",
            reflow("      \"License\" shall mean the terms and\n      conditions for use.  It is so.\n"),
        )
    }

    @Test
    fun theTitles_keepTheirLines() {
        assertTrue(
            reflow(raw("license_apache_2_0.txt")).startsWith(
                "Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/\n\n" +
                    "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n\n1. Definitions.\n\n"
            )
        )
        assertTrue(
            reflow(raw("license_gpl_3_0.txt"))
                .startsWith("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n\nCopyright (C) 2007")
        )
    }

    @Test
    fun theSampleNotices_keepTheirLines() {
        assertTrue(
            reflow(raw("license_apache_2_0.txt")).contains(
                "Copyright [yyyy] [name of copyright owner]\n\n" +
                    "Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
                    "you may not use this file except in compliance with the License.\n"
            )
        )
        assertTrue(
            reflow(raw("license_gpl_3_0.txt")).contains(
                "<one line to give the program's name and a brief idea of what it does.>\n" +
                    "Copyright (C) <year>  <name of author>\n\n" +
                    "This program is free software: you can redistribute it and/or modify\n"
            )
        )
    }

    @Test
    fun items_commentsAndRules_keepTheirLines() {
        assertEquals(
            "Redistribution and use are permitted provided that:\n" +
                "1. Redistributions of source code must retain the above notice.\n" +
                "2. Redistributions in binary form must reproduce it.",
            reflow(
                "Redistribution and use are permitted provided that:\n" +
                    "1. Redistributions of source code must retain\n   the above notice.\n" +
                    "2. Redistributions in binary form must\n   reproduce it.\n"
            ),
        )
        assertEquals(
            "// Copyright (c) 2009 The RE2 Authors.\n// Redistribution and use\n// are permitted.",
            reflow("// Copyright (c) 2009 The RE2 Authors.\n// Redistribution and use\n// are permitted.\n"),
        )
        assertEquals("PCRE LICENCE\n------------", reflow("PCRE LICENCE\n------------\n"))
    }

    @Test
    fun aNewTitle_startsNewTerms() {
        // After one license's terms, the next license's prose is prose again.
        val two = raw("license_apache_2_0.txt") + "\n\n" + raw("license_apache_2_0.txt")
        val second = reflow(two).substringAfter("limitations under the License.")
        assertTrue(
            second.contains(
                "\n\n\"License\" shall mean the terms and conditions for use, reproduction, and distribution " +
                    "as defined by Sections 1 through 9 of this document.\n\n"
            )
        )
    }
}
