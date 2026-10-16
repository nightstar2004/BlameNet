using System;
using System.IO;
using System.Runtime.InteropServices;

/// <summary>
/// 原生文件夹选择对话框（Windows IFileDialog，即资源管理器样式）。
/// 用法：FolderPicker.exe <输出文件> [对话框标题]
/// 把用户选择的文件夹完整路径写入 <输出文件>（UTF-8）；取消时写入空字符串并返回退出码 1。
/// </summary>
internal static class Program
{
    [DllImport("ole32.dll")]
    private static extern int CoInitializeEx(IntPtr pvReserved, uint dwCoInit);

    [DllImport("ole32.dll")]
    private static extern void CoUninitialize();

    [ComImport]
    [Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")]
    private class FileOpenDialog { }

    [ComImport]
    [Guid("42F85136-DB7E-439C-85F1-E4075D135FC8")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileDialog
    {
        [PreserveSig]
        int Show(IntPtr parent);
        void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
        void SetFileTypeIndex(uint iFileType);
        void GetFileTypeIndex(out uint piFileType);
        void Advise(IntPtr pfde, out uint pdwCookie);
        void Unadvise(uint dwCookie);
        void SetOptions(uint fos);
        void GetOptions(out uint pfos);
        void SetDefaultFolder(IShellItem psi);
        void SetFolder(IShellItem psi);
        void GetFolder(out IShellItem ppsi);
        void GetCurrentSelection(out IShellItem ppsi);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
        void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
        void GetResult(out IShellItem ppsi);
        void AddPlace(IShellItem psi, int fdap);
        void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
        void Close(int hr);
        void SetClientGuid(ref Guid guid);
        void ClearClientData();
        void SetFilter(IntPtr pFilter);
    }

    [ComImport]
    [Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        void GetParent(out IShellItem ppsi);
        void GetDisplayName(uint sigdnName, out IntPtr ppszName);
        void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
        void Compare(IShellItem psi, uint hint, out int piOrder);
    }

    private const uint FOS_PICKFOLDERS = 0x00000020;
    private const uint FOS_FORCEFILESYSTEM = 0x00000040;
    private const uint FOS_PATHMUSTEXIST = 0x00000800;
    private const uint SIGDN_FILESYSPATH = 0x80058000;

    private static int Main(string[] args)
    {
        string outFile = args.Length > 0 ? args[0] : "folder-result.txt";
        string title = args.Length > 1 ? args[1] : "选择文件夹";

        string result = PickFolder(title);
        try
        {
            // UTF-8 with BOM，便于 Inno Setup 的 LoadStringsFromFile 正确识别中文路径
            File.WriteAllText(outFile, result ?? "", new System.Text.UTF8Encoding(true));
        }
        catch
        {
            // 写结果失败不影响对话框本身
        }
        return string.IsNullOrEmpty(result) ? 1 : 0;
    }

    private static string PickFolder(string title)
    {
        CoInitializeEx(IntPtr.Zero, 2 /* COINIT_APARTMENTTHREADED */);
        try
        {
            var dialog = (IFileDialog)new FileOpenDialog();
            dialog.SetOptions(FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
            dialog.SetTitle(title);

            int hr = dialog.Show(IntPtr.Zero);
            if (hr != 0)
            {
                return null; // 用户取消
            }

            dialog.GetResult(out IShellItem item);
            item.GetDisplayName(SIGDN_FILESYSPATH, out IntPtr pszName);
            string path = Marshal.PtrToStringUni(pszName);
            Marshal.FreeCoTaskMem(pszName);
            return path;
        }
        finally
        {
            CoUninitialize();
        }
    }
}
