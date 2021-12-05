package com.stirante.runechanger.util;

import com.sun.jna.platform.win32.Advapi32Util;
import mslinks.ShellLink;
import mslinks.ShellLinkException;
import mslinks.ShellLinkHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER;

public class ShortcutUtils {
    private static final Logger log = LoggerFactory.getLogger(ShortcutUtils.class);
    private static final String REGISTRY_DESKTOP_PATH =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders\\";
    private static final String REGISTRY_DESKTOP_KEY = "Desktop";

    public static void createShortcut(File directory, String linkName, String fileName) throws IOException {
        try {
            String dir = PathUtils.getWorkingDirectory();
            File iconFile = new File(dir + File.separator + "icon.ico");
            ShellLink sl = new ShellLink();
            ShellLinkHelper slh = new ShellLinkHelper(sl);
            sl.setIconLocation(iconFile.getAbsolutePath());
            sl.setWorkingDir(dir);
            sl.setName(linkName);
            String absolutePath = new File(directory, linkName + ".lnk").getAbsolutePath();
            Path targetPath = directory.toPath().toAbsolutePath();
            String root = targetPath.getRoot().toString();
            String path = targetPath.subpath(0, targetPath.getNameCount()).toString();
            slh
                    .setLocalTarget(root, path, ShellLinkHelper.Options.ForceTypeFile)
                    .saveTo(absolutePath);
            log.info(String.format("Created shortcut for %s in %s", fileName, absolutePath));
        } catch (AccessDeniedException | ShellLinkException e) {
            log.error("An error occurred while creating a shortcut", e);
        }
    }

    public static void createMenuShortcuts() throws IOException {
        File menuFolder =
                new File(System.getenv("AppData") + "\\Microsoft\\Windows\\Start Menu\\Programs\\RuneChanger");
        menuFolder.mkdir();
        createShortcut(menuFolder, "RuneChanger", "open.bat");
        createShortcut(menuFolder, "RuneChanger (Debug)", "debug.bat");
    }

    public static void createDesktopShortcut() throws IOException {
        String path =
                Advapi32Util.registryGetStringValue(HKEY_CURRENT_USER, REGISTRY_DESKTOP_PATH, REGISTRY_DESKTOP_KEY);
        File folder = new File(path);
        createShortcut(folder, "RuneChanger", "open.bat");
    }

}
