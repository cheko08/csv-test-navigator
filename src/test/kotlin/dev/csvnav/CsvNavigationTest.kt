package dev.csvnav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CsvNavigationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "config/ProcessDetails.csv",
            "name,description\nLogin,Logs in\nLogout,Logs out\n\"Pay, Bill\",Pays\n",
        )
        // Data files are named after the process; the data name is the row key (first column).
        myFixture.addFileToProject("testData/Login.csv", "key,user,password\nuser1,alice,pw\nuser2,bob,pw\n")
        myFixture.addFileToProject("testData/auth/Logout.csv", "key,reason\nuser1,timeout\n")
        myFixture.addFileToProject("testData/Other.csv", "key,x\nuser1,not this one\n")
    }

    /** Opens a test case CSV at tests/[name] (so `../config` resolves) and returns targets at the caret. */
    private fun targets(text: String, name: String = "cases.csv"): List<PsiElement> {
        open(text, name)
        val handler = GotoDeclarationHandler.EP_NAME.findExtensionOrFail(CsvGotoDeclarationHandler::class.java)
        return handler.getGotoDeclarationTargets(null, myFixture.editor.caretModel.offset, myFixture.editor)
            ?.toList().orEmpty()
    }

    private fun open(text: String, name: String = "cases.csv") {
        val file = myFixture.addFileToProject("tests/$name", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        if ("<caret>" in text) myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
    }

    private fun describe(targets: List<PsiElement>): List<String> = targets.map {
        when (it) {
            is CsvRowElement -> "${it.containingFile.virtualFile.path.substringAfter("/src/")}:${it.line + 1}"
            is PsiFile -> it.virtualFile.path.substringAfter("/src/")
            else -> it.toString()
        }
    }

    fun testProcessGoesToRowInProcessDetails() {
        assertEquals(listOf("config/ProcessDetails.csv:3"), describe(targets("id,process,data\nTC1,Log<caret>out,user1\n")))
    }

    fun testQuotedProcessName() {
        assertEquals(
            listOf("config/ProcessDetails.csv:4"),
            describe(targets("id,process,data\nTC1,\"Pay<caret>, Bill\",user1\n")),
        )
    }

    fun testDataGoesToRowInFileNamedAfterProcess() {
        assertEquals(listOf("testData/Login.csv:3"), describe(targets("id,process,data\nTC1,Login,us<caret>er2\n")))
    }

    fun testDataFileFoundInSubfolder() {
        assertEquals(
            listOf("testData/auth/Logout.csv:2"),
            describe(targets("id,process,data\nTC1,Logout,us<caret>er1\n")),
        )
    }

    fun testDataWithoutProcessOrUnknownKeyGivesNothing() {
        assertEmpty(targets("id,process,data\nTC1,,us<caret>er1\n"))
        assertEmpty(targets("id,process,data\nTC1,Login,us<caret>er9\n", "b.csv"))
        assertEmpty(targets("id,process,data\nTC1,NoSuchProcess,us<caret>er1\n", "c.csv"))
    }

    fun testUnknownNamesAndUnmappedCellsGiveNothing() {
        assertEmpty(targets("id,process,data\nTC1,Mis<caret>sing,user1\n"))
        assertEmpty(targets("id,process,data\nT<caret>C1,Login,user1\n", "b.csv"))
        assertEmpty(targets("id,pro<caret>cess,data\nTC1,Login,user1\n", "c.csv"))
    }

    fun testIgnoresNonCsvFiles() {
        assertEmpty(targets("id,process,data\nTC1,Lo<caret>gin,user1\n", "cases.txt"))
    }

    fun testNavigatingOpensFileAtRow() {
        val target = targets("id,process,data\nTC1,Log<caret>out,user1\n").single()
        (target as CsvRowElement).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("ProcessDetails.csv", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }

    /** Runs highlighting and returns (text, attributes key, description) for our highlights, sorted by position. */
    private fun highlights(text: String, name: String = "cases.csv"): List<Triple<String, TextAttributesKey?, String?>> {
        open(text, name)
        val keys = setOf(CsvNavColors.PROCESS, CsvNavColors.DATA, CsvNavColors.UNRESOLVED)
        return myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey in keys }
            .sortedWith(compareBy({ it.startOffset }, { it.forcedTextAttributesKey?.externalName }))
            .map { Triple(text.substring(it.startOffset, it.endOffset), it.forcedTextAttributesKey, it.description) }
    }

    fun testHighlightsProcessesGreenAndDataBlue() {
        val found = highlights("id,process,data\nTC1,Login,user1\nTC2,\"Logout\",\n")
        assertEquals(
            listOf(
                Triple("Login", CsvNavColors.PROCESS, null),
                Triple("user1", CsvNavColors.DATA, null),
                Triple("Logout", CsvNavColors.PROCESS, null),
            ),
            found,
        )
    }

    fun testMultipleProcessAndDataColumns() {
        val header = "Id,Process1,Data1,Process2,Data2,Process3,Data3\n"
        assertEquals(
            listOf("config/ProcessDetails.csv:3"),
            describe(targets(header + "TC1,Login,user1,Log<caret>out,user1,Login,user2\n")),
        )
        // Data2 uses Process2's file (Logout.csv), Data3 uses Process3's (Login.csv).
        assertEquals(
            listOf("testData/auth/Logout.csv:2"),
            describe(targets(header + "TC1,Login,user2,Logout,us<caret>er1,Login,user2\n", "b.csv")),
        )
        assertEquals(
            listOf("testData/Login.csv:3"),
            describe(targets(header + "TC1,Logout,user1,Logout,user1,Login,us<caret>er2\n", "c.csv")),
        )
    }

    fun testDataWithoutMatchingNumberUsesNearestProcessToTheLeft() {
        assertEquals(
            listOf("testData/auth/Logout.csv:2"),
            describe(targets("Id,ProcessA,Data,ProcessB,DataX\nTC1,Login,user2,Logout,us<caret>er1\n")),
        )
    }

    fun testHighlightsEveryProcessAndDataColumn() {
        val found = highlights("Id,Process1,Data1,Process2,Data2,Notes\nTC1,Login,user1,Logout,user1,Login\n")
        assertEquals(
            listOf(
                Triple("Login", CsvNavColors.PROCESS, null),
                Triple("user1", CsvNavColors.DATA, null),
                Triple("Logout", CsvNavColors.PROCESS, null),
                Triple("user1", CsvNavColors.DATA, null),
            ),
            found,
        )
    }

    fun testMissingReferencesAreRedInsteadOfColoured() {
        val found = highlights("Id,Process1,Data1,Process2,Data2\nTC1,Missing,user1,Login,user9\nTC2,Login,user1,,user1\n")
        assertEquals(
            listOf(
                Triple("Missing", CsvNavColors.UNRESOLVED, "Cannot resolve Process1 'Missing' in ../config/ProcessDetails.csv"),
                Triple("user1", CsvNavColors.UNRESOLVED, "Cannot resolve Data1 'user1' in ../testData/**/Missing.csv"),
                Triple("Login", CsvNavColors.PROCESS, null),
                Triple("user9", CsvNavColors.UNRESOLVED, "Cannot resolve Data2 'user9' in ../testData/**/Login.csv"),
                Triple("Login", CsvNavColors.PROCESS, null),
                Triple("user1", CsvNavColors.DATA, null),
                Triple("user1", CsvNavColors.UNRESOLVED, "Cannot resolve Data2 'user1': this row has no Process2"),
            ),
            found,
        )
    }

    fun testUnresolvedMessagesForSingleColumns() {
        val found = highlights("id,process,data\nTC1,Missing,user1\nTC2,Login,user9\nTC3,,user1\n")
            .filter { it.second == CsvNavColors.UNRESOLVED }
        assertEquals(
            listOf(
                Triple("Missing", CsvNavColors.UNRESOLVED, "Cannot resolve process 'Missing' in ../config/ProcessDetails.csv"),
                // Process "Missing" has no data file, so its data can't resolve either.
                Triple("user1", CsvNavColors.UNRESOLVED, "Cannot resolve data 'user1' in ../testData/**/Missing.csv"),
                Triple("user9", CsvNavColors.UNRESOLVED, "Cannot resolve data 'user9' in ../testData/**/Login.csv"),
                Triple("user1", CsvNavColors.UNRESOLVED, "Cannot resolve data 'user1': this row has no process"),
            ),
            found,
        )
    }

    fun testResolvedReferencesAreNotUnderlined() {
        val found = highlights("id,process,data\nTC1,Login,user1\nTC2,Logout,user1\n")
        assertTrue(found.none { it.second == CsvNavColors.UNRESOLVED })
    }

    fun testDoesNotHighlightFilesWithoutMappedColumns() {
        assertEmpty(highlights("name,description\nLogin,Logs in\n", "notATest.csv"))
    }
}
