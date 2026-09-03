using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace GrokRemote.Desktop;

public class App : Application
{
    private Window? _window;

    public App()
    {
        var resources = new XamlControlsResources();
        Resources.MergedDictionaries.Add(resources);
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}
