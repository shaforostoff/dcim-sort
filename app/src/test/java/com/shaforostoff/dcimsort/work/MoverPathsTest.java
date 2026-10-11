package com.shaforostoff.dcimsort.work;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MoverPathsTest {

    @Test
    public void groupedFolderGoesUnderTheSource() {
        assertEquals("DCIM/Camera/Paris-2024-05/",
                Mover.insertableRelativePath("DCIM/Camera/", "Paris-2024-05"));
        assertEquals("Pictures/Trips/Paris-2024-05/",
                Mover.insertableRelativePath("Pictures/Trips", "Paris-2024-05"));
    }

    @Test
    public void noGroupingStaysInTheSource() {
        assertEquals("DCIM/Camera/", Mover.insertableRelativePath("DCIM/Camera/", null));
    }

    @Test
    public void sourceThatCannotHoldImagesIsRedirectedToCamera() {
        assertEquals("DCIM/Camera/Paris-2024-05/",
                Mover.insertableRelativePath("Download/", "Paris-2024-05"));
        assertEquals("DCIM/Camera/", Mover.insertableRelativePath("Download/", null));
    }
}
