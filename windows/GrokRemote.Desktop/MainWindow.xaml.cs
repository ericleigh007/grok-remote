using System.IO;
using System.Text.Json;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.Web.WebView2.Core;
using Windows.Graphics;
using Windows.UI;
using WinRT.Interop;

namespace GrokRemote.Desktop;

public sealed class MainWindow : Window
{
    private readonly NativeSpeech _speech = new();
    private readonly WebView2 _web = new();
    private readonly TextBlock _status = new();
    private bool _started;

    public MainWindow()
    {
        Title = "Grok Remote";
        TryResize(520, 900);

        _status.Text = "Starting…";
        _status.Foreground = new SolidColorBrush(Color.FromArgb(255, 139, 155, 184));
        _status.FontSize = 16;
        _status.HorizontalAlignment = HorizontalAlignment.Center;
        _status.VerticalAlignment = VerticalAlignment.Center;
        _web.DefaultBackgroundColor = Color.FromArgb(255, 11, 16, 32);

        var root = new Grid { Background = new SolidColorBrush(Color.FromArgb(255, 11, 16, 32)) };
        root.Children.Add(_web);
        root.Children.Add(_status);
        Content = root;

        Closed += (_, _) => _speech.Dispose();
        _speech.Partial += text => DispatchJs(new { type = "stt_partial", text });
        _speech.Final += text => DispatchJs(new { type = "stt_final", text });
        _speech.Error += text => DispatchJs(new { type = "stt_error", text });
        _speech.ListeningChanged += on => DispatchJs(new { type = "stt_listening", listening = on });
        root.Loaded += async (_, _) => await StartAsync();
    }

    private WebView2 Web => _web;
    private TextBlock Status => _status;

    private void TryResize(int width, int height)
    {
        try
        {
            var hwnd = WindowNative.GetWindowHandle(this);
            var id = Win32Interop.GetWindowIdFromWindow(hwnd);
            var appWindow = AppWindow.GetFromWindowId(id);
            appWindow?.Resize(new SizeInt32(width, height));
        }
        catch
        {
            /* ignore */
        }
    }

    private async Task StartAsync()
    {
        if (_started)
            return;
        _started = true;

        var args = Environment.GetCommandLineArgs().Skip(1).ToArray();
        var url = UrlResolver.Resolve(args);
        if (string.IsNullOrWhiteSpace(url))
            url = await PromptUrlAsync();
        if (string.IsNullOrWhiteSpace(url))
        {
            Close();
            return;
        }
        UrlResolver.Save(url);

        var (host, token) = UrlResolver.BridgeConfig();
        var nav = url;
        if (!string.IsNullOrWhiteSpace(token) &&
            !string.IsNullOrWhiteSpace(host) &&
            url.StartsWith(host, StringComparison.OrdinalIgnoreCase) &&
            url.IndexOf("token=", StringComparison.OrdinalIgnoreCase) < 0)
        {
            nav = url + (url.Contains('?', StringComparison.Ordinal) ? "&" : "?") +
                  "token=" + Uri.EscapeDataString(token);
        }

        try
        {
            var userData = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "GrokRemote",
                "webview");
            Directory.CreateDirectory(userData);
            Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", userData);
            await Web.EnsureCoreWebView2Async();
        }
        catch (Exception ex)
        {
            Status.Text = "WebView2 runtime missing (install Edge / Evergreen WebView2).\n" + ex.Message;
            return;
        }

        Web.CoreWebView2!.Settings.AreDefaultContextMenusEnabled = true;
        Web.CoreWebView2.Settings.IsStatusBarEnabled = false;
        Web.CoreWebView2.PermissionRequested += (_, ev) =>
        {
            if (ev.PermissionKind is CoreWebView2PermissionKind.Microphone
                or CoreWebView2PermissionKind.Notifications)
            {
                ev.State = CoreWebView2PermissionState.Allow;
            }
        };
        Web.CoreWebView2.NewWindowRequested += (_, ev) =>
        {
            ev.Handled = true;
            Web.CoreWebView2.Navigate(ev.Uri);
        };
        await Web.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(
            "window.GrokNative={tts:true,stt:true};");
        Web.CoreWebView2.WebMessageReceived += OnWebMessage;
        Web.NavigationCompleted += (_, _) =>
        {
            Status.Visibility = Visibility.Collapsed;
            PushVoices();
        };
        Status.Text = "Opening " + url;
        Web.CoreWebView2.Navigate(nav);
    }

    private async Task<string?> PromptUrlAsync()
    {
        var box = new TextBox { Text = "https://", PlaceholderText = "https://your-pc.tailnet.ts.net" };
        var dialog = new ContentDialog
        {
            Title = "Grok Remote — PC address",
            Content = box,
            PrimaryButtonText = "Open",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = (Content as FrameworkElement)?.XamlRoot,
        };
        var result = await dialog.ShowAsync();
        if (result != ContentDialogResult.Primary)
            return null;
        return UrlResolver.Normalize(box.Text);
    }

    private void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        string raw;
        try { raw = e.TryGetWebMessageAsString(); }
        catch { raw = e.WebMessageAsJson; }
        if (string.IsNullOrWhiteSpace(raw))
            return;
        JsonDocument doc;
        try { doc = JsonDocument.Parse(raw); }
        catch { return; }
        var root = doc.RootElement;
        var type = root.TryGetProperty("type", out var t) ? t.GetString() : "";
        var voice = root.TryGetProperty("voice", out var v) ? v.GetString() : null;
        var text = root.TryGetProperty("text", out var tx) ? tx.GetString() : "";
        switch (type)
        {
            case "list_voices":
                PushVoices();
                break;
            case "speak":
                _ = _speech.SpeakAsync(text ?? "", voice, preview: false);
                break;
            case "preview":
                _ = _speech.SpeakAsync(
                    string.IsNullOrWhiteSpace(text)
                        ? "This is a preview of the selected voice for Grok replies."
                        : text!,
                    voice,
                    preview: true);
                break;
            case "stop":
                _speech.StopSpeaking();
                break;
            case "listen_start":
                _ = _speech.StartListenAsync();
                break;
            case "listen_stop":
                _ = _speech.StopListenAsync();
                break;
            case "set_voice":
                _speech.SetVoice(voice);
                break;
        }
    }

    private void PushVoices()
    {
        var voices = _speech.ListVoices()
            .Select(v => new { id = v.Id, name = v.Name, lang = v.Lang, local = v.Local })
            .ToList();
        DispatchJs(new { type = "voices", voices });
    }

    private void DispatchJs(object payload)
    {
        var json = JsonSerializer.Serialize(payload);
        DispatcherQueue.TryEnqueue(async () =>
        {
            if (Web.CoreWebView2 == null)
                return;
            var script = "window.dispatchEvent(new CustomEvent('grok-native',{detail:" + json + "}));";
            try { await Web.CoreWebView2.ExecuteScriptAsync(script); }
            catch { /* navigating */ }
        });
    }
}
