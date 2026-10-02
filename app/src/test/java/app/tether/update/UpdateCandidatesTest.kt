package app.tether.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCandidatesTest {

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, manifest: String = "update.json") =
        GhRelease(draft = draft, prerelease = pre, assets = listOf(GhAsset(manifest, "https://x/$tag/$manifest"), GhAsset("$tag.apk", "https://x/$tag.apk")))

    private fun tags(c: List<Pair<GhRelease, GhAsset>>) = c.map { it.second.url.split('/')[3] }

    // API order: newest first.
    private val releases = listOf(
        release("v2.0.0-beta.2", pre = true),
        release("v2.1.0-draft", draft = true),
        release("v1.7.2-debug.16", pre = true, manifest = "update-debug.json"),
        release("v2.0.0-beta.1", pre = true),
        release("v1.7.1"),
        release("v1.7.0"),
    )

    @Test fun stableUsersOnlySeeTheNewestStableRelease() {
        assertEquals(listOf("v1.7.1"), tags(updateCandidates(releases, "update.json", debug = false, beta = false)))
    }

    @Test fun betaUsersAlsoSeeTheNewestBeta() {
        assertEquals(listOf("v1.7.1", "v2.0.0-beta.2"), tags(updateCandidates(releases, "update.json", debug = false, beta = true)))
    }

    @Test fun debugBuildsSeeDebugPreReleasesOnly() {
        assertEquals(listOf("v1.7.2-debug.16"), tags(updateCandidates(releases, "update-debug.json", debug = true, beta = false)))
    }

    @Test fun draftsNeverCount() {
        val onlyDraft = listOf(release("v9.0.0", draft = true), release("v9.0.0-beta.1", pre = true, draft = true))
        assertEquals(emptyList<String>(), tags(updateCandidates(onlyDraft, "update.json", debug = false, beta = true)))
    }

    @Test fun noBetaYetMeansStableOnly() {
        val stableOnly = listOf(release("v1.7.1"), release("v1.7.0"))
        assertEquals(listOf("v1.7.1"), tags(updateCandidates(stableOnly, "update.json", debug = false, beta = true)))
    }
}
