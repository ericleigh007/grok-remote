using System.Text.RegularExpressions;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Media.SpeechRecognition;
using Windows.Media.SpeechSynthesis;

namespace GrokRemote.Desktop;

internal sealed record VoiceDto(string Id, string Name, string Lang, bool Local);

/// <summary>
/// Windows.Media speech (WinRT). This is how Windows 11 Natural voices are
/// actually exposed — not SAPI 5 / System.Speech, which is the dump heap.
/// </summary>
internal sealed class NativeSpeech : IDisposable
{
    private readonly SpeechSynthesizer _synth = new();
    private readonly MediaPlayer _player = new();
    private SpeechRecognizer? _stt;
    private string? _voiceId;

    public event Action<string>? Partial;
    public event Action<string>? Final;
    public event Action<string>? Error;
    public event Action<bool>? ListeningChanged;

    public IReadOnlyList<VoiceDto> ListVoices()
    {
        return SpeechSynthesizer.AllVoices
            .Select(v => new VoiceDto(
                v.Id,
                string.IsNullOrWhiteSpace(v.DisplayName) ? v.Id : v.DisplayName,
                v.Language,
                true))
            .OrderBy(v => v.Lang, StringComparer.OrdinalIgnoreCase)
            .ThenBy(v => v.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public void SetVoice(string? id)
    {
        if (string.IsNullOrWhiteSpace(id))
            return;
        _voiceId = id;
        var hit = SpeechSynthesizer.AllVoices.FirstOrDefault(v =>
            string.Equals(v.Id, id, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(v.DisplayName, id, StringComparison.OrdinalIgnoreCase));
        if (hit != null)
            _synth.Voice = hit;
    }

    public async Task SpeakAsync(string text, string? voiceId, bool preview)
    {
        var clean = Regex.Replace(text, "```[\\s\\S]*?```", " code block ");
        clean = Regex.Replace(clean, "[#*_`>]{1,6}", " ").Trim();
        clean = clean[..Math.Min(clean.Length, preview ? 220 : 1400)];
        if (string.IsNullOrWhiteSpace(clean))
            return;
        if (!string.IsNullOrWhiteSpace(voiceId))
            SetVoice(voiceId);
        try
        {
            _player.Pause();
            var stream = await _synth.SynthesizeTextToStreamAsync(clean);
            _player.Source = MediaSource.CreateFromStream(stream, stream.ContentType);
            _player.Play();
        }
        catch (Exception ex)
        {
            Error?.Invoke("TTS failed: " + ex.Message);
        }
    }

    public void StopSpeaking()
    {
        try { _player.Pause(); } catch { /* ignore */ }
    }

    public async Task StartListenAsync()
    {
        try
        {
            await StopListenAsync();
            var reco = new SpeechRecognizer();
            reco.Constraints.Add(
                new SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.Dictation, "dictation"));
            var compile = await reco.CompileConstraintsAsync();
            if (compile.Status != SpeechRecognitionResultStatus.Success)
            {
                Error?.Invoke("Speech recognition is not available. Enable online speech in Windows Settings.");
                reco.Dispose();
                return;
            }
            reco.HypothesisGenerated += (_, e) =>
            {
                var t = e.Hypothesis?.Text;
                if (!string.IsNullOrWhiteSpace(t))
                    Partial?.Invoke(t);
            };
            reco.ContinuousRecognitionSession.ResultGenerated += (_, e) =>
            {
                if (e.Result?.Status == SpeechRecognitionResultStatus.Success &&
                    !string.IsNullOrWhiteSpace(e.Result.Text))
                    Final?.Invoke(e.Result.Text);
            };
            reco.ContinuousRecognitionSession.Completed += (_, e) =>
            {
                ListeningChanged?.Invoke(false);
                if (e.Status != SpeechRecognitionResultStatus.Success &&
                    e.Status != SpeechRecognitionResultStatus.UserCanceled)
                    Error?.Invoke("Mic ended: " + e.Status);
            };
            _stt = reco;
            await reco.ContinuousRecognitionSession.StartAsync();
            ListeningChanged?.Invoke(true);
        }
        catch (Exception ex)
        {
            ListeningChanged?.Invoke(false);
            Error?.Invoke("Mic failed: " + ex.Message);
        }
    }

    public async Task StopListenAsync()
    {
        var reco = _stt;
        _stt = null;
        if (reco == null)
            return;
        try { await reco.ContinuousRecognitionSession.StopAsync(); }
        catch { /* ignore */ }
        reco.Dispose();
        ListeningChanged?.Invoke(false);
    }

    public void Dispose()
    {
        _ = StopListenAsync();
        StopSpeaking();
        _player.Dispose();
        _synth.Dispose();
    }
}
