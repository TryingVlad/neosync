package dev.neosync.boox;

import static org.junit.Assert.assertEquals;

import dev.neosync.boox.library.OnyxTitle;
import org.junit.Test;

public class OnyxTitleTest {

    @Test
    public void onyxTitleUsedForPdf() {
        String[] ta = OnyxTitle.choose("Developmental predictors of young adult borderline"
                + " personality disorder", " Sinclaire M. O’Grady", "s12888-023-04515-3.pdf");
        assertEquals("Developmental predictors of young adult borderline personality disorder",
                ta[0]);
        assertEquals("Sinclaire M. O’Grady", ta[1]);
    }

    @Test
    public void authorEmptyWhenOnyxHasNone() {
        String[] ta = OnyxTitle.choose("Library of Babel: Drawing Kit", null, "drawing-kit.pdf");
        assertEquals("Library of Babel: Drawing Kit", ta[0]);
        assertEquals("", ta[1]);
    }

    @Test
    public void fallsBackToFileNameWhenOnyxTitleEmpty() {
        String file = "My Book (z-library.sk, 1lib.sk).pdf";
        assertEquals("My Book", OnyxTitle.choose(null, null, file)[0]);
        assertEquals("My Book", OnyxTitle.choose("", "", file)[0]);
        assertEquals("My Book", OnyxTitle.choose("   ", "", file)[0]);
    }

    @Test
    public void fileNameTitleIgnored() {
        assertEquals("Notes 2", OnyxTitle.choose("Notes_2.pdf", "", "Notes_2.pdf")[0]);
        assertEquals("Notes 2", OnyxTitle.choose("notes_2", "", "Notes_2.pdf")[0]);
    }

    @Test
    public void hexIdTitleIgnored() {
        String[] ta = OnyxTitle.choose("27204b954259466ca75d44629b0a3b74", "",
                "Report-final.pdf");
        assertEquals("Report-final", ta[0]);
        assertEquals("Report-final",
                OnyxTitle.choose("27204B954259466CA75D44629B0A3B74", "", "Report-final.pdf")[0]);
    }
}
