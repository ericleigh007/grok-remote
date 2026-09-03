using System.IO;
using System.Text.Json;

namespace GrokRemote.Desktop;

internal static class UrlResolver
{
    private static readonly string SettingsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "GrokRemote",
        "desktop.json");

    public static string? Resolve(string[] args)
    {
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i] is "--url" or "-u" && i + 1 < args.Length)
                return Normalize(args[i + 1]);
            if (args[i].StartsWith("https://", StringComparison.OrdinalIgnoreCase) ||
                args[i].StartsWith("http://", StringComparison.OrdinalIgnoreCase))
                return Normalize(args[i]);
        }

        var saved = ReadSaved();
        if (!string.IsNullOrWhiteSpace(saved))
            return Normalize(saved);

        var (host, _) = BridgeConfig();
        return Normalize(host);
    }

    public static void Save(string url)
    {
        try
        {
            var dir = Path.GetDirectoryName(SettingsPath);
            if (!string.IsNullOrEmpty(dir))
                Directory.CreateDirectory(dir);
            File.WriteAllText(
                SettingsPath,
                JsonSerializer.Serialize(new Dictionary<string, string> { ["baseUrl"] = url }));
        }
        catch
        {
            /* ignore */
        }
    }

    public static (string? host, string? token) BridgeConfig()
    {
        foreach (var path in CandidateConfigPaths())
        {
            if (!File.Exists(path))
                continue;
            try
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(path));
                var root = doc.RootElement;
                var host = root.TryGetProperty("public_host", out var h) ? h.GetString() : null;
                var token = root.TryGetProperty("remote_token", out var t) ? t.GetString() : null;
                if (!string.IsNullOrWhiteSpace(host))
                    return (host.Trim().TrimEnd('/'), token);
            }
            catch
            {
                /* next */
            }
        }
        return (null, null);
    }

    private static string? ReadSaved()
    {
        try
        {
            if (!File.Exists(SettingsPath))
                return null;
            using var doc = JsonDocument.Parse(File.ReadAllText(SettingsPath));
            return doc.RootElement.TryGetProperty("baseUrl", out var u) ? u.GetString() : null;
        }
        catch
        {
            return null;
        }
    }

    private static IEnumerable<string> CandidateConfigPaths()
    {
        yield return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "GrokRemote",
            "config.json");
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 8 && dir != null; i++, dir = dir.Parent)
            yield return Path.Combine(dir.FullName, "config.json");
        yield return Path.Combine(Directory.GetCurrentDirectory(), "config.json");
    }

    public static string? Normalize(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return null;
        var s = raw.Trim();
        if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            s = "https://" + s;
        return s.TrimEnd('/');
    }
}
