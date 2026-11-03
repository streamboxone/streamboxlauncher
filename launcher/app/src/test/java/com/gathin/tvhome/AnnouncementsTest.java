package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AnnouncementsTest {
    @Test public void anUnseenAnnouncementIsUnread() {
        assertTrue(Announcements.unread("News", null));
    }

    @Test public void aDismissedAnnouncementStaysDismissedUntilItChanges() {
        String dismissed = Announcements.fingerprint("News");
        assertFalse(Announcements.unread("News", dismissed));
        assertTrue(Announcements.unread("News, updated", dismissed));
    }

    @Test public void anEmptyFileIsNeverShown() {
        assertFalse(Announcements.unread(Announcements.clean(" \r\n "), null));
    }

    @Test public void lineEndingsAndOuterSpaceDoNotCountAsAChange() {
        assertEquals(Announcements.fingerprint(Announcements.clean("One\nTwo\n")),
                Announcements.fingerprint(Announcements.clean("﻿One\r\nTwo  \r\n")));
    }

    @Test public void aWordChangeDoes() {
        assertNotEquals(Announcements.fingerprint("One"), Announcements.fingerprint("Two"));
    }

    @Test public void controlCharactersAreDroppedAndLengthIsCapped() {
        assertEquals("ab\ncd", Announcements.clean("a\u0007b\ncd‮"));
        assertEquals(4000, Announcements.clean("x".repeat(5000)).length());
    }

    @Test public void aCheckIsDueAtTheStartAndAfterTheInterval() {
        assertTrue(Announcements.due(0, 5, 100));
        assertFalse(Announcements.due(50, 100, 100));
        assertTrue(Announcements.due(50, 150, 100));
    }

    @Test public void onlyPlainTextOverHttpsIsRead() {
        assertTrue(Announcements.acceptable("https://example.com/a.txt", 200, "text/plain; charset=utf-8"));
        assertFalse(Announcements.acceptable("http://example.com/a.txt", 200, "text/plain"));
        assertFalse(Announcements.acceptable("https://example.com/a.txt", 404, "text/plain"));
        assertFalse(Announcements.acceptable("https://example.com/a.txt", 200, "text/html"));
        assertFalse(Announcements.acceptable("https://example.com/a.txt", 200, null));
    }
}
