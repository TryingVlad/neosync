package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.kosync.SyncSettings;

import org.junit.Test;

public class ServerLabelTest {
    @Test public void emptyIsTheDefaultServer() {
        assertEquals("sync.crosspointreader.com", SyncSettings.serverLabel(""));
    }
    @Test public void schemeAndTrailingSlashesAreDropped() {
        assertEquals("sync.example.org/kosync", SyncSettings.serverLabel("https://sync.example.org/kosync//"));
    }
    @Test public void bareHostIsShownAsIs() {
        assertEquals("my.server:8443", SyncSettings.serverLabel("my.server:8443"));
    }
    @Test public void baseUrlAddsHttpsToABareHost() {
        assertEquals("https://my.server", SyncSettings.baseUrlOf("my.server/"));
        assertEquals("https://sync.crosspointreader.com", SyncSettings.baseUrlOf(""));
    }
}
