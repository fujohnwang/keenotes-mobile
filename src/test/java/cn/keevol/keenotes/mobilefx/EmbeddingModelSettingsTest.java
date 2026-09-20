package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.search.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.util.List;

import static org.junit.Assert.*;

public class EmbeddingModelSettingsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void migratesLegacyConfigurationAndPersistsIndependentModelsWithOneSelection() throws Exception {
        Path file = temp.getRoot().toPath().resolve("settings.properties");
        SettingsService settings = new SettingsService(file);
        EmbeddingConfig old = new EmbeddingConfig(true, "http://localhost:11434/v1", "nomic-embed-text", "old-secret-key", "doc: ", "query: ");
        settings.setEmbeddingConfig(old); settings.save();
        EmbeddingModelCatalog migrated = new SettingsService(file).getEmbeddingModelCatalog();
        assertEquals(1, migrated.models().size());
        assertEquals(old, migrated.configuration());
        assertEquals("legacy", migrated.selectedId());
        SavedEmbeddingModel added = new SavedEmbeddingModel("second", "Remote provider", new EmbeddingConfig(false,
                "https://embedding.example/v1", "model-b", "second-secret-key", "", ""));
        settings.setEmbeddingModelCatalog(new EmbeddingModelCatalog(List.of(migrated.selected(), added), "legacy", true));
        settings.save();
        SettingsService reopened = new SettingsService(file);
        assertEquals(2, reopened.getEmbeddingModelCatalog().models().size());
        assertEquals(old, reopened.getEmbeddingConfig());
        reopened.setEmbeddingModelCatalog(new EmbeddingModelCatalog(reopened.getEmbeddingModelCatalog().models(), "second", true));
        reopened.save();
        SettingsService switched = new SettingsService(file);
        assertEquals("second", switched.getEmbeddingModelCatalog().selectedId());
        assertEquals("model-b", switched.getEmbeddingConfig().model());
        assertTrue(switched.getEmbeddingConfig().usable());
        assertEquals("second-secret-key", switched.getEmbeddingConfig().apiKey());
        assertEquals("old-secret-key", switched.getEmbeddingModelCatalog().models().getFirst().config().apiKey());
        switched.setEmbeddingModelCatalog(new EmbeddingModelCatalog(switched.getEmbeddingModelCatalog().models(), "", false));
        switched.save();
        SettingsService off = new SettingsService(file);
        assertEquals(2, off.getEmbeddingModelCatalog().models().size());
        assertNull(off.getEmbeddingModelCatalog().selected());
        assertFalse(off.getEmbeddingConfig().usable());
        assertEquals("second-secret-key", off.getEmbeddingModelCatalog().models().getLast().config().apiKey());
        String contents = Files.readString(file);
        assertFalse(contents.contains("old-secret-key"));
        assertFalse(contents.contains("second-secret-key"));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingModelCatalog(List.of(added, new SavedEmbeddingModel("duplicate", "Duplicate", added.config())), "second", true));
    }

    @Test public void disabledLegacyModelMigratesToNoneWithoutDiscardingTheSavedModel() {
        SettingsService settings = new SettingsService(temp.getRoot().toPath().resolve("legacy.properties"));
        settings.setEmbeddingConfig(new EmbeddingConfig(false, "http://localhost:11434/v1", "test", "secret", "", ""));
        EmbeddingModelCatalog catalog = settings.getEmbeddingModelCatalog();
        assertEquals(1, catalog.models().size());
        assertEquals("secret", catalog.models().getFirst().config().apiKey());
        assertNull(catalog.selected());
        assertEquals("", catalog.selectedId());
        assertFalse(catalog.configuration().usable());
    }
}
