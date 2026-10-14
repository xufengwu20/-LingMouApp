package com.example.lingmouapp.db

import com.example.lingmouapp.shared.RecipeStepDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库层的 JVM 单测 —— 跑的是**真的 SQLite**（内存库），不是 mock。
 *
 * 这组用例同时验证了三件事：
 *   1. SQLDelight 生成的代码与 .sq 里的 SQL 确实能建表、能读写；
 *   2. 五张表之间的关联（会话↔分心、菜谱↔步骤）行为正确；
 *   3. 自增主键的获取方式（last_insert_rowid）在真实驱动上可用。
 */
class KnowledgeRepositoryTest {

    private fun newRepo(): KnowledgeRepository =
        KnowledgeRepository(openDatabase(JvmDatabaseDriverFactory()))

    @Test
    fun insertAndReadNote() {
        val repo = newRepo()
        val id = repo.insertNote(
            title = "红烧肉做法",
            content = "先焯水，再炒糖色",
            mode = KnowledgeRepository.MODE_COOKING,
            sourceTranscript = "今天我们做红烧肉",
            createdAt = 1_000L
        )
        assertTrue("主键应该是正的: $id", id > 0)

        val note = repo.note(id)
        assertNotNull(note)
        assertEquals("红烧肉做法", note!!.title)
        assertEquals("先焯水，再炒糖色", note.content)
        assertEquals(KnowledgeRepository.MODE_COOKING, note.mode)
        assertEquals(1_000L, note.createdAt)
        assertEquals(1L, repo.noteCount())
    }

    @Test
    fun notesAreOrderedByCreatedAtDescending() {
        val repo = newRepo()
        repo.insertNote("旧", "a", "FOCUS", createdAt = 100L)
        repo.insertNote("新", "b", "FOCUS", createdAt = 300L)
        repo.insertNote("中", "c", "FOCUS", createdAt = 200L)

        assertEquals(listOf("新", "中", "旧"), repo.notes().map { it.title })
    }

    @Test
    fun sessionCanBeLinkedToNote() {
        val repo = newRepo()
        val noteId = repo.insertNote("专注总结", "今天很专注", "FOCUS")
        val sessionId = repo.insertSession(
            startedAt = 1_000L,
            endedAt = 6_000L,
            distractionCount = 2L
        )

        val created = repo.latestSession()
        assertNotNull(created)
        assertEquals(5_000L, created!!.durationMs)   // endedAt - startedAt
        assertNull(created.noteId)                   // 还没关联

        repo.attachNoteToSession(sessionId, noteId)
        assertEquals(noteId, repo.latestSession()!!.noteId)
    }

    @Test
    fun distractionsAreScopedToSession() {
        val repo = newRepo()
        val s1 = repo.insertSession(0L, 1_000L)
        val s2 = repo.insertSession(2_000L, 3_000L)

        repo.insertDistraction(s1, happenedAt = 100L, awayMillis = 3_200L)
        repo.insertDistraction(s1, happenedAt = 200L, awayMillis = 4_000L)
        repo.insertDistraction(
            s2,
            happenedAt = 300L,
            faceCount = 2L,
            kind = KnowledgeRepository.KIND_INTRUDER
        )
        repo.insertDistraction()  // 不属于任何会话

        assertEquals(2, repo.distractionsOf(s1).size)
        assertEquals(1, repo.distractionsOf(s2).size)
        assertEquals(4L, repo.distractionCount())
        assertEquals(
            KnowledgeRepository.KIND_INTRUDER,
            repo.distractionsOf(s2).first().kind
        )
    }

    @Test
    fun recipeIsSavedWithOrderedSteps() {
        val repo = newRepo()
        val recipeId = repo.insertRecipe(
            title = "番茄炒蛋",
            summary = "10 分钟快手菜",
            sourceTranscript = "打三个鸡蛋",
            steps = listOf(
                RecipeStepDraft("打散三个鸡蛋", "1分钟"),
                RecipeStepDraft("热锅下油", ""),
                RecipeStepDraft("翻炒出锅", "5分钟")
            )
        )

        val steps = repo.stepsOf(recipeId)
        assertEquals(3, steps.size)
        assertEquals(listOf(1L, 2L, 3L), steps.map { it.stepIndex })
        assertEquals("打散三个鸡蛋", steps[0].instruction)
        assertEquals("1分钟", steps[0].durationHint)
        assertEquals("翻炒出锅", steps[2].instruction)
        assertEquals(3L, repo.recipeStepCount())
    }

    @Test
    fun deletingRecipeAlsoDeletesItsSteps() {
        val repo = newRepo()
        val keep = repo.insertRecipe("保留", "", steps = listOf(RecipeStepDraft("A")))
        val drop = repo.insertRecipe("删除", "", steps = listOf(RecipeStepDraft("B"), RecipeStepDraft("C")))
        assertEquals(3L, repo.recipeStepCount())

        repo.deleteRecipe(drop)

        assertEquals(1L, repo.recipeCount())
        assertEquals(1L, repo.recipeStepCount())
        assertNull(repo.recipe(drop))
        assertNotNull(repo.recipe(keep))
        assertTrue(repo.stepsOf(drop).isEmpty())
    }

    @Test
    fun deletingNoteDoesNotTouchOthers() {
        val repo = newRepo()
        val a = repo.insertNote("A", "x", "FOCUS")
        repo.insertNote("B", "y", "FOCUS")

        repo.deleteNote(a)

        assertEquals(1L, repo.noteCount())
        assertNull(repo.note(a))
    }

    @Test
    fun wipeHelpersClearEachTable() {
        val repo = newRepo()
        repo.insertNote("n", "c", "FOCUS")
        repo.insertSession(0L, 10L)
        repo.insertDistraction()
        val r = repo.insertRecipe("r", "", steps = listOf(RecipeStepDraft("s")))

        repo.clearNotes()
        repo.clearSessions()
        repo.clearDistractions()
        assertEquals(0L, repo.noteCount())
        assertEquals(0L, repo.distractionCount())
        assertTrue(repo.sessions().isEmpty())

        repo.clearRecipes()
        assertEquals(0L, repo.recipeCount())
        assertEquals(0L, repo.recipeStepCount())
        assertNull(repo.recipe(r))
    }
}
