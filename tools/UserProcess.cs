// SYSTEM supervisor launches grok/python as the installing user with no stored password.
// Token sources (in order):
//   1) MSV1_0 S4U logon — works while the user is logged off
//   2) WTSQueryUserToken — interactive session of that user
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

namespace GrokRemote
{
    public static class UserProcess
    {
        const uint TOKEN_ALL_ACCESS = 0x000F01FF;
        const uint CREATE_NO_WINDOW = 0x08000000;
        const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
        const uint STARTF_USESTDHANDLES = 0x00000100;
        const uint HANDLE_FLAG_INHERIT = 0x00000001;
        const uint GENERIC_WRITE = 0x40000000;
        const uint FILE_SHARE_READ = 0x00000001;
        const uint FILE_SHARE_WRITE = 0x00000002;
        const uint OPEN_ALWAYS = 4;
        const uint FILE_ATTRIBUTE_NORMAL = 0x80;
        const uint FILE_END = 2;
        const int SecurityImpersonation = 2;
        const int TokenPrimary = 1;
        const int MsV1_0S4ULogon = 12;
        const int TokenSessionId = 12;
        const uint SE_PRIVILEGE_ENABLED = 0x00000002;
        const int ANYSIZE_ARRAY = 1;

        [StructLayout(LayoutKind.Sequential)]
        struct SECURITY_ATTRIBUTES
        {
            public int nLength;
            public IntPtr lpSecurityDescriptor;
            public int bInheritHandle;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct STARTUPINFO
        {
            public int cb;
            public string lpReserved;
            public string lpDesktop;
            public string lpTitle;
            public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
            public short wShowWindow, cbReserved2;
            public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_INFORMATION
        {
            public IntPtr hProcess, hThread;
            public int dwProcessId, dwThreadId;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct LUID { public uint LowPart; public int HighPart; }

        [StructLayout(LayoutKind.Sequential)]
        struct LUID_AND_ATTRIBUTES { public LUID Luid; public uint Attributes; }

        [StructLayout(LayoutKind.Sequential)]
        struct TOKEN_PRIVILEGES
        {
            public int PrivilegeCount;
            public LUID_AND_ATTRIBUTES Privileges;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct LSA_STRING
        {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct LSA_UNICODE_STRING
        {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct QUOTA_LIMITS
        {
            public IntPtr PagedPoolLimit, NonPagedPoolLimit, MinimumWorkingSetSize, MaximumWorkingSetSize, PagefileLimit;
            public long TimeLimit;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct TOKEN_SOURCE
        {
            [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
            public byte[] SourceName;
            public LUID SourceIdentifier;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WTS_SESSION_INFO
        {
            public uint SessionId;
            public IntPtr pWinStationName;
            public uint State;
        }

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool OpenProcessToken(IntPtr ProcessHandle, uint DesiredAccess, out IntPtr TokenHandle);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool LookupPrivilegeValue(string lpSystemName, string lpName, out LUID lpLuid);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool AdjustTokenPrivileges(IntPtr TokenHandle, bool DisableAll, ref TOKEN_PRIVILEGES NewState, int BufferLength, IntPtr PreviousState, IntPtr ReturnLength);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool DuplicateTokenEx(IntPtr hExistingToken, uint dwDesiredAccess, IntPtr lpTokenAttributes, int ImpersonationLevel, int TokenType, out IntPtr phNewToken);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcessAsUser(IntPtr hToken, string lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes, IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory, ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern IntPtr CreateFile(string lpFileName, uint dwDesiredAccess, uint dwShareMode, ref SECURITY_ATTRIBUTES lpSecurityAttributes, uint dwCreationDisposition, uint dwFlagsAndAttributes, IntPtr hTemplateFile);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetHandleInformation(IntPtr hObject, uint dwMask, uint dwFlags);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetFilePointerEx(IntPtr hFile, long liDistanceToMove, IntPtr lpNewFilePointer, uint dwMoveMethod);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr hObject);

        [DllImport("kernel32.dll")]
        static extern IntPtr GetCurrentProcess();

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSQueryUserToken(uint sessionId, out IntPtr phToken);

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSEnumerateSessions(IntPtr hServer, int Reserved, int Version, out IntPtr ppSessionInfo, out int pCount);

        [DllImport("wtsapi32.dll")]
        static extern void WTSFreeMemory(IntPtr pMemory);

        [DllImport("wtsapi32.dll", CharSet = CharSet.Unicode)]
        static extern bool WTSQuerySessionInformation(IntPtr hServer, uint sessionId, int wtsInfoClass, out IntPtr ppBuffer, out int pBytesReturned);

        [DllImport("userenv.dll", SetLastError = true)]
        static extern bool CreateEnvironmentBlock(out IntPtr lpEnvironment, IntPtr hToken, bool bInherit);

        [DllImport("userenv.dll", SetLastError = true)]
        static extern bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

        [DllImport("secur32.dll")]
        static extern int LsaConnectUntrusted(out IntPtr LsaHandle);

        [DllImport("secur32.dll")]
        static extern int LsaRegisterLogonProcess(ref LSA_STRING LogonProcessName, out IntPtr LsaHandle, out ulong SecurityMode);

        [DllImport("secur32.dll")]
        static extern int LsaLookupAuthenticationPackage(IntPtr LsaHandle, ref LSA_STRING PackageName, out uint AuthenticationPackage);

        [DllImport("secur32.dll")]
        static extern int LsaLogonUser(IntPtr LsaHandle, ref LSA_STRING OriginName, int LogonType, uint AuthenticationPackage, IntPtr AuthenticationInformation, int AuthenticationInformationLength, IntPtr LocalGroups, ref TOKEN_SOURCE SourceContext, out IntPtr ProfileBuffer, out int ProfileBufferLength, out LUID LogonId, out IntPtr Token, out QUOTA_LIMITS Quotas, out int SubStatus);

        [DllImport("secur32.dll")]
        static extern int LsaDeregisterLogonProcess(IntPtr LsaHandle);

        [DllImport("secur32.dll")]
        static extern int LsaFreeReturnBuffer(IntPtr Buffer);

        [DllImport("advapi32.dll")]
        static extern int LsaNtStatusToWinError(int Status);

        public static string LastError { get; private set; }

        static void EnablePrivilege(string name)
        {
            IntPtr tok;
            if (!OpenProcessToken(GetCurrentProcess(), 0x0028, out tok)) return; // TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY
            try
            {
                TOKEN_PRIVILEGES tp = new TOKEN_PRIVILEGES();
                tp.PrivilegeCount = 1;
                tp.Privileges.Attributes = SE_PRIVILEGE_ENABLED;
                if (!LookupPrivilegeValue(null, name, out tp.Privileges.Luid)) return;
                AdjustTokenPrivileges(tok, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero);
            }
            finally { CloseHandle(tok); }
        }

        static LSA_STRING AnsiLsa(string s)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(s);
            IntPtr buf = Marshal.AllocHGlobal(bytes.Length + 1);
            Marshal.Copy(bytes, 0, buf, bytes.Length);
            Marshal.WriteByte(buf, bytes.Length, 0);
            return new LSA_STRING { Length = (ushort)bytes.Length, MaximumLength = (ushort)(bytes.Length + 1), Buffer = buf };
        }

        static void FreeLsa(ref LSA_STRING s)
        {
            if (s.Buffer != IntPtr.Zero) { Marshal.FreeHGlobal(s.Buffer); s.Buffer = IntPtr.Zero; }
        }

        // LocalSystem S4U: user token without a password, including while logged off.
        static IntPtr TryS4U(string domain, string user)
        {
            EnablePrivilege("SeTcbPrivilege");
            IntPtr lsa;
            ulong mode;
            LSA_STRING procName = AnsiLsa("GrokRemote");
            int st = LsaRegisterLogonProcess(ref procName, out lsa, out mode);
            FreeLsa(ref procName);
            if (st != 0)
            {
                st = LsaConnectUntrusted(out lsa);
                if (st != 0)
                {
                    LastError = "LsaConnect/Register failed " + LsaNtStatusToWinError(st);
                    return IntPtr.Zero;
                }
            }
            try
            {
                LSA_STRING pkg = AnsiLsa("MICROSOFT_AUTHENTICATION_PACKAGE_V1_0");
                uint pkgId;
                st = LsaLookupAuthenticationPackage(lsa, ref pkg, out pkgId);
                FreeLsa(ref pkg);
                if (st != 0)
                {
                    LastError = "LookupAuthenticationPackage " + LsaNtStatusToWinError(st);
                    return IntPtr.Zero;
                }

                byte[] userBytes = Encoding.Unicode.GetBytes(user);
                byte[] domBytes = Encoding.Unicode.GetBytes(domain ?? "");
                int header = 16 + 2 * Marshal.SizeOf(typeof(LSA_UNICODE_STRING));
                int total = header + userBytes.Length + 2 + domBytes.Length + 2;
                IntPtr auth = Marshal.AllocHGlobal(total);
                try
                {
                    for (int i = 0; i < total; i++) Marshal.WriteByte(auth, i, 0);
                    Marshal.WriteInt32(auth, 0, MsV1_0S4ULogon); // MessageType
                    Marshal.WriteInt32(auth, 4, 0);               // Flags
                    IntPtr userBuf = IntPtr.Add(auth, header);
                    Marshal.Copy(userBytes, 0, userBuf, userBytes.Length);
                    IntPtr domBuf = IntPtr.Add(userBuf, userBytes.Length + 2);
                    if (domBytes.Length > 0) Marshal.Copy(domBytes, 0, domBuf, domBytes.Length);

                    // UNICODE_STRING UserPrincipalName at offset 8
                    Marshal.WriteInt16(auth, 8, (short)userBytes.Length);
                    Marshal.WriteInt16(auth, 10, (short)(userBytes.Length + 2));
                    Marshal.WriteIntPtr(auth, 16, userBuf);
                    // UNICODE_STRING DomainName at offset 8+sizeof(UNICODE_STRING)=24 on x64
                    int domOff = 8 + Marshal.SizeOf(typeof(LSA_UNICODE_STRING));
                    Marshal.WriteInt16(auth, domOff, (short)domBytes.Length);
                    Marshal.WriteInt16(auth, domOff + 2, (short)(domBytes.Length + 2));
                    Marshal.WriteIntPtr(auth, domOff + 8, domBuf);

                    LSA_STRING origin = AnsiLsa("GrokRemote");
                    TOKEN_SOURCE src = new TOKEN_SOURCE();
                    src.SourceName = Encoding.ASCII.GetBytes("GrokRem\0");
                    IntPtr profile;
                    int profileLen, subStatus;
                    LUID logonId;
                    IntPtr token;
                    QUOTA_LIMITS quotas;
                    // LogonType 8 = LOGON32_LOGON_NETWORK_CLEARTEXT / SecurityLogonType.NetworkCleartext = 8; S4U uses 3 (Network) often.
                    st = LsaLogonUser(lsa, ref origin, 3, pkgId, auth, total, IntPtr.Zero, ref src,
                        out profile, out profileLen, out logonId, out token, out quotas, out subStatus);
                    FreeLsa(ref origin);
                    if (profile != IntPtr.Zero) LsaFreeReturnBuffer(profile);
                    if (st != 0)
                    {
                        LastError = "LsaLogonUser S4U nt=" + st + " win=" + LsaNtStatusToWinError(st) + " sub=" + subStatus;
                        return IntPtr.Zero;
                    }
                    IntPtr primary;
                    if (!DuplicateTokenEx(token, TOKEN_ALL_ACCESS, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out primary))
                    {
                        LastError = "DuplicateTokenEx S4U " + new Win32Exception(Marshal.GetLastWin32Error()).Message;
                        CloseHandle(token);
                        return IntPtr.Zero;
                    }
                    CloseHandle(token);
                    LastError = "s4u";
                    return primary;
                }
                finally { Marshal.FreeHGlobal(auth); }
            }
            finally { LsaDeregisterLogonProcess(lsa); }
        }

        static IntPtr TrySessionToken(string domain, string user)
        {
            EnablePrivilege("SeTcbPrivilege");
            IntPtr pInfo;
            int count;
            if (!WTSEnumerateSessions(IntPtr.Zero, 0, 1, out pInfo, out count))
            {
                LastError = "WTSEnumerateSessions " + new Win32Exception(Marshal.GetLastWin32Error()).Message;
                return IntPtr.Zero;
            }
            try
            {
                int size = Marshal.SizeOf(typeof(WTS_SESSION_INFO));
                string want = string.IsNullOrEmpty(domain) ? user : (domain + "\\" + user);
                for (int i = 0; i < count; i++)
                {
                    WTS_SESSION_INFO si = Marshal.PtrToStructure<WTS_SESSION_INFO>(IntPtr.Add(pInfo, i * size));
                    if (si.SessionId == 0) continue;
                    IntPtr buf; int bytes;
                    if (!WTSQuerySessionInformation(IntPtr.Zero, si.SessionId, 5, out buf, out bytes)) continue; // WTSUserName = 5
                    string sessUser = Marshal.PtrToStringUni(buf);
                    WTSFreeMemory(buf);
                    if (!WTSQuerySessionInformation(IntPtr.Zero, si.SessionId, 7, out buf, out bytes)) continue; // WTSDomainName = 7
                    string sessDom = Marshal.PtrToStringUni(buf);
                    WTSFreeMemory(buf);
                    bool match = string.Equals(sessUser, user, StringComparison.OrdinalIgnoreCase);
                    if (!match) continue;
                    if (!string.IsNullOrEmpty(domain) && !string.Equals(sessDom, domain, StringComparison.OrdinalIgnoreCase)
                        && !string.Equals(sessDom, ".", StringComparison.OrdinalIgnoreCase)
                        && !string.Equals(domain, Environment.MachineName, StringComparison.OrdinalIgnoreCase))
                    {
                        // still allow if username matched uniquely
                    }
                    IntPtr sessTok;
                    if (!WTSQueryUserToken(si.SessionId, out sessTok))
                    {
                        LastError = "WTSQueryUserToken session " + si.SessionId + " " + new Win32Exception(Marshal.GetLastWin32Error()).Message;
                        continue;
                    }
                    IntPtr primary;
                    if (!DuplicateTokenEx(sessTok, TOKEN_ALL_ACCESS, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out primary))
                    {
                        LastError = "DuplicateTokenEx session " + new Win32Exception(Marshal.GetLastWin32Error()).Message;
                        CloseHandle(sessTok);
                        continue;
                    }
                    CloseHandle(sessTok);
                    LastError = "session " + si.SessionId + " " + sessDom + "\\" + sessUser;
                    return primary;
                }
                LastError = "no session for " + want;
                return IntPtr.Zero;
            }
            finally { WTSFreeMemory(pInfo); }
        }

        public static IntPtr AcquireToken(string domain, string user, string sid)
        {
            EnablePrivilege("SeAssignPrimaryTokenPrivilege");
            EnablePrivilege("SeIncreaseQuotaPrivilege");
            EnablePrivilege("SeImpersonatePrivilege");
            EnablePrivilege("SeTcbPrivilege");
            LastError = "";
            IntPtr t = TryS4U(domain, user);
            if (t != IntPtr.Zero) return t;
            string s4uErr = LastError;
            t = TrySessionToken(domain, user);
            if (t != IntPtr.Zero) return t;
            LastError = "S4U: " + s4uErr + " | session: " + LastError;
            if (!string.IsNullOrEmpty(sid))
            {
                // last resort: any process already running as that SID (explorer, etc.)
                t = TryTokenFromProcessSid(sid);
                if (t != IntPtr.Zero) return t;
            }
            return IntPtr.Zero;
        }

        static IntPtr TryTokenFromProcessSid(string sid)
        {
            try
            {
                foreach (Process p in Process.GetProcesses())
                {
                    IntPtr ht;
                    try
                    {
                        if (!OpenProcessToken(p.Handle, 0x000A, out ht)) continue; // TOKEN_DUPLICATE | TOKEN_QUERY
                    }
                    catch { continue; }
                    try
                    {
                        WindowsIdentity id = new WindowsIdentity(ht);
                        if (!string.Equals(id.User.Value, sid, StringComparison.OrdinalIgnoreCase)) continue;
                        IntPtr primary;
                        if (DuplicateTokenEx(ht, TOKEN_ALL_ACCESS, IntPtr.Zero, SecurityImpersonation, TokenPrimary, out primary))
                        {
                            LastError = "process " + p.ProcessName + " pid=" + p.Id;
                            return primary;
                        }
                    }
                    catch { }
                    finally { CloseHandle(ht); }
                }
            }
            catch (Exception ex) { LastError = "process-sid " + ex.Message; }
            return IntPtr.Zero;
        }

        public static void CloseToken(IntPtr token)
        {
            if (token != IntPtr.Zero) CloseHandle(token);
        }

        public static int Start(IntPtr token, string exe, string arguments, string cwd, string outLog, string errLog)
        {
            if (token == IntPtr.Zero) throw new InvalidOperationException("No user token: " + LastError);
            EnablePrivilege("SeAssignPrimaryTokenPrivilege");
            EnablePrivilege("SeIncreaseQuotaPrivilege");

            SECURITY_ATTRIBUTES sa = new SECURITY_ATTRIBUTES();
            sa.nLength = Marshal.SizeOf(sa);
            sa.bInheritHandle = 1;

            IntPtr hOut = CreateFile(outLog, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, ref sa, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
            if (hOut == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error(), "stdout " + outLog);
            IntPtr hErr = CreateFile(errLog, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, ref sa, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
            if (hErr == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error(), "stderr " + errLog);
            SetFilePointerEx(hOut, 0, IntPtr.Zero, FILE_END);
            SetFilePointerEx(hErr, 0, IntPtr.Zero, FILE_END);
            SetHandleInformation(hOut, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);
            SetHandleInformation(hErr, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT);

            IntPtr env;
            if (!CreateEnvironmentBlock(out env, token, false))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateEnvironmentBlock");

            string cmd = "\"" + exe + "\"";
            if (!string.IsNullOrEmpty(arguments)) cmd += " " + arguments;
            StringBuilder cl = new StringBuilder(cmd);

            STARTUPINFO si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(si);
            si.dwFlags = (int)STARTF_USESTDHANDLES;
            si.hStdOutput = hOut;
            si.hStdError = hErr;
            si.hStdInput = IntPtr.Zero;
            // Stay in session 0 as the user (no desktop flash). Empty desktop is fine for services.
            si.lpDesktop = "";

            PROCESS_INFORMATION pi;
            uint flags = CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW;
            bool ok = CreateProcessAsUser(token, exe, cl, IntPtr.Zero, IntPtr.Zero, true, flags, env, cwd, ref si, out pi);
            int err = Marshal.GetLastWin32Error();
            DestroyEnvironmentBlock(env);
            CloseHandle(hOut);
            CloseHandle(hErr);
            if (!ok) throw new Win32Exception(err, "CreateProcessAsUser " + exe + " (" + LastError + ")");
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
            return pi.dwProcessId;
        }

        public static bool CurrentProcessIsSystem()
        {
            using (WindowsIdentity id = WindowsIdentity.GetCurrent())
            {
                return id != null && id.IsSystem;
            }
        }
    }
}
