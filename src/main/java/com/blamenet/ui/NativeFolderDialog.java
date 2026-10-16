package com.blamenet.ui;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

/**
 * Windows 原生「浏览文件夹」对话框（资源管理器样式）。
 * <p>
 * 通过 JNA 调用 shell32 的 SHBrowseForFolderW，并使用 BIF_NEWDIALOGSTYLE 标志，
 * 弹出 Windows 原生、带编辑框与「新建文件夹」按钮的资源管理器样式对话框，
 * 替代 Swing 的 JFileChooser（后者是跨平台自绘，非 Windows 原生外观）。
 */
public final class NativeFolderDialog {

    /** 只返回文件系统目录。 */
    private static final int BIF_RETURNONLYFSDIRS = 0x00000001;
    /** 显示编辑框。 */
    private static final int BIF_EDITBOX = 0x00000010;
    /** 资源管理器新样式（右侧列表 + 新建文件夹按钮）。 */
    private static final int BIF_NEWDIALOGSTYLE = 0x00000040;

    /** COM 单线程公寓初始化。 */
    private static final int COINIT_APARTMENTTHREADED = 0x2;

    /** 路径缓冲长度（MAX_PATH）。 */
    private static final int MAX_PATH = 260;

    /** BROWSEINFOW 结构（按引用传递）。 */
    public static class BROWSEINFO extends Structure implements Structure.ByReference {
        public WinDef.HWND hwndOwner;
        public Pointer pidlRoot;
        public char[] pszDisplayName = new char[MAX_PATH];
        public WString lpszTitle;
        public int ulFlags;
        public Pointer lpfn;
        public Pointer lParam;
        public int iImage;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("hwndOwner", "pidlRoot", "pszDisplayName",
                    "lpszTitle", "ulFlags", "lpfn", "lParam", "iImage");
        }
    }

    /** 扩展 Shell32 函数（SHBrowseForFolderW / SHGetPathFromIDListW）。 */
    private interface Shell32Ext extends StdCallLibrary {
        Shell32Ext INSTANCE = Native.load("shell32", Shell32Ext.class);

        Pointer SHBrowseForFolderW(BROWSEINFO lpbi);

        boolean SHGetPathFromIDListW(Pointer pidl, char[] pszPath);
    }

    private NativeFolderDialog() {
    }

    /**
     * 弹出 Windows 原生文件夹选择对话框。
     *
     * @param title 对话框标题
     * @return 用户选择的文件夹完整路径；取消时返回 {@code null}
     */
    public static String pickFolder(String title) {
        boolean initialized = Ole32.INSTANCE
                .CoInitializeEx(null, COINIT_APARTMENTTHREADED).intValue() >= 0;
        try {
            BROWSEINFO info = new BROWSEINFO();
            info.hwndOwner = null;
            info.pidlRoot = null;
            info.ulFlags = BIF_RETURNONLYFSDIRS | BIF_EDITBOX | BIF_NEWDIALOGSTYLE;
            info.lpszTitle = new WString(title == null ? "选择文件夹" : title);
            info.write();

            Pointer pidl = Shell32Ext.INSTANCE.SHBrowseForFolderW(info);
            if (pidl == null) {
                return null;
            }
            try {
                char[] path = new char[MAX_PATH];
                if (Shell32Ext.INSTANCE.SHGetPathFromIDListW(pidl, path)) {
                    return Native.toString(path).trim();
                }
                return null;
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(pidl);
            }
        } finally {
            if (initialized) {
                Ole32.INSTANCE.CoUninitialize();
            }
        }
    }
}
