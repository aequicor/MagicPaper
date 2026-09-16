# Isolated, per-grant UI Automation host. Never call SetFocus, SendInput or clipboard APIs.
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, WindowsBase, System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
public static class WindowCapture {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr dc, uint flags);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hwnd);
    public static string Png(IntPtr hwnd) {
        RECT r;
        if (IsIconic(hwnd) || !GetWindowRect(hwnd, out r)) throw new InvalidOperationException();
        int w = r.Right-r.Left, h = r.Bottom-r.Top;
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long)w*h > 40000000) throw new InvalidOperationException();
        using (var bitmap = new Bitmap(w,h)) {
            using (var graphics = Graphics.FromImage(bitmap)) {
                IntPtr dc = graphics.GetHdc();
                try { if (!PrintWindow(hwnd, dc, 2)) throw new InvalidOperationException(); }
                finally { graphics.ReleaseHdc(dc); }
            }
            // Protected/unsupported compositors commonly return an empty frame. Do not replace with a desktop crop.
            bool nonBlack = false;
            for (int y=0; y<h; y+=Math.Max(1,h/100)) for (int x=0; x<w; x+=Math.Max(1,w/100))
                if ((bitmap.GetPixel(x,y).ToArgb() & 0xffffff) != 0) nonBlack = true;
            if (!nonBlack) throw new InvalidOperationException();
            double scale = Math.Min(1.0, 1600.0/Math.Max(w,h));
            using (var resized = new Bitmap(bitmap, Math.Max(1,(int)(w*scale)), Math.Max(1,(int)(h*scale))))
            using (var output = new MemoryStream()) { resized.Save(output,ImageFormat.Png); return Convert.ToBase64String(output.ToArray()); }
        }
    }
}
'@
$windows = @{}
$elements = @{}
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
function RuntimeId($element) { return ($element.GetRuntimeId() -join ':') }
function Value($element) {
    if ($element.Current.IsPassword) { return '' }
    $pattern = $null
    if ($element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) { return [string]$pattern.Current.Value }
    if ($element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) { return [string]$pattern.Current.Value }
    if ($element.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern)) {
        return ([string]$pattern.Current.HorizontalScrollPercent + ':' + [string]$pattern.Current.VerticalScrollPercent)
    }
    return ''
}
function Fingerprint($element) {
    $c = $element.Current
    return ([string]$c.Name + [char]0 + $c.ControlType.ProgrammaticName + [char]0 + (Value $element) + [char]0 + $c.IsEnabled)
}
function Actions($element) {
    $result = @()
    if (!$element.Current.IsEnabled -or $element.Current.IsPassword) { return ,$result }
    $pattern = $null
    if ($element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) { $result += 'invoke' }
    if ($element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern) -and !$pattern.Current.IsReadOnly) { $result += 'set_value' }
    if ($element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern) -and !$pattern.Current.IsReadOnly -and $pattern.Current.SmallChange -gt 0) { $result += 'increment'; $result += 'decrement' }
    elseif ($element.TryGetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern, [ref]$pattern) -and $pattern.Current.VerticallyScrollable) { $result += 'increment'; $result += 'decrement' }
    return ,$result
}
function ValidateWindow($id) {
    if (!$windows.ContainsKey($id)) { throw 'stale' }
    $w = $windows[$id]
    if ([System.Diagnostics.Process]::GetProcessById($w.pid).StartTime.Ticks -ne $w.started) { throw 'stale' }
    $fresh = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$w.hwnd)
    if ((RuntimeId $fresh) -ne $w.runtime -or $fresh.Current.ProcessId -ne $w.pid) { throw 'stale' }
    return $w
}
function Short($value) { $s = [string]$value; return $s.Substring(0,[Math]::Min($s.Length,1000)) }
function InspectWindow($id) {
    $w = ValidateWindow $id
    $script:elements.Clear()
    $output = New-Object 'System.Collections.Generic.List[object]'
    $queue = New-Object 'System.Collections.Generic.Queue[object]'
    $queue.Enqueue(@{node=$w.element; parent=''; depth=0})
    $seen = @{}
    while ($queue.Count -gt 0 -and $output.Count -lt 500) {
        $entry = $queue.Dequeue(); $node = $entry.node
        $runtime = RuntimeId $node
        if ($seen.ContainsKey($runtime) -or $entry.depth -ge 24) { continue }
        $seen[$runtime] = $true
        $key = [Guid]::NewGuid().ToString()
        $available = Actions $node
        $secure = $node.Current.IsPassword
        $script:elements[$key] = @{element=$node; window=$id; fingerprint=(Fingerprint $node); actions=$available; runtime=$runtime}
        $output.Add(@{element_id=$key; parent_id=$entry.parent; role=$node.Current.ControlType.ProgrammaticName;
            name=$(if ($secure) { 'Protected field' } else { Short $node.Current.Name });
            value=$(if ($secure) { '' } else { Short (Value $node) }); actions=@($available)})
        if (!$secure) {
            $child = $walker.GetFirstChild($node)
            $count = 0
            while ($null -ne $child -and $count -lt 500 -and $queue.Count -lt 1000) {
                $queue.Enqueue(@{node=$child; parent=$key; depth=($entry.depth+1)})
                $child = $walker.GetNextSibling($child); $count++
            }
        }
    }
    return @{window_id=$id; elements=@($output.ToArray()); bounded=$true; limit=500}
}
function Request($request) {
    $action = [string]$request.action
    if ($action -eq 'windows') {
        $script:windows.Clear(); $script:elements.Clear()
        $output = @()
        $roots = [System.Windows.Automation.AutomationElement]::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($root in $roots) {
            $c = $root.Current
            if ($c.NativeWindowHandle -eq 0 -or $c.ProcessId -eq $PID) { continue }
            try { $process = [System.Diagnostics.Process]::GetProcessById($c.ProcessId); $started = $process.StartTime.Ticks }
            catch { continue } # Elevated/inaccessible processes are not candidates.
            $id = [Guid]::NewGuid().ToString()
            $script:windows[$id] = @{element=$root; hwnd=$c.NativeWindowHandle; pid=$c.ProcessId; started=$started; runtime=(RuntimeId $root)}
            $output += @{window_id=$id; application=$process.ProcessName; title=(Short $c.Name)}
        }
        return @{windows=@($output); selection='Select the window requested by the user; inaccessible processes are omitted.'}
    }
    $id = [string]$request.window_id
    $w = ValidateWindow $id
    if ($action -eq 'inspect') { return InspectWindow $id }
    if ($action -eq 'screenshot') {
        try { $png = [WindowCapture]::Png([IntPtr]$w.hwnd) } catch { throw 'capture' }
        $null = ValidateWindow $id
        return @{window_id=$id; png=$png}
    }
    $key = [string]$request.element_id
    if (!$script:elements.ContainsKey($key)) { throw 'stale' }
    $saved = $script:elements[$key]; $element = $saved.element
    if ($saved.window -ne $id -or $action -notin $saved.actions -or $action -notin (Actions $element) -or
        $saved.runtime -ne (RuntimeId $element) -or $saved.fingerprint -cne (Fingerprint $element)) { throw 'stale' }
    # A retained reference must still descend from the selected window; never retarget by coordinates or labels.
    $ancestor = $element; $contained = $false
    for ($depth=0; $depth -lt 32 -and $null -ne $ancestor; $depth++) {
        if ((RuntimeId $ancestor) -eq $w.runtime) { $contained = $true; break }
        $ancestor = $walker.GetParent($ancestor)
    }
    if (!$contained) { throw 'stale' }
    $script:elements.Clear()
    switch ($action) {
        'invoke' { $element.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
        'set_value' {
            if ($null -eq $request.text -or ([string]$request.text).Length -gt 10000) { throw 'unsupported' }
            $element.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue([string]$request.text)
        }
        { $_ -in 'increment','decrement' } {
            $pattern = $null
            if ($element.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$pattern)) {
                $c = $pattern.Current
                $step = $c.SmallChange * $(if ($action -eq 'increment') { 1 } else { -1 })
                $pattern.SetValue([Math]::Max($c.Minimum,[Math]::Min($c.Maximum,$c.Value+$step)))
            } else {
                $pattern = $element.GetCurrentPattern([System.Windows.Automation.ScrollPattern]::Pattern)
                $amount = if ($action -eq 'increment') { [System.Windows.Automation.ScrollAmount]::SmallIncrement } else { [System.Windows.Automation.ScrollAmount]::SmallDecrement }
                $pattern.Scroll([System.Windows.Automation.ScrollAmount]::NoAmount,$amount)
            }
        }
        default { throw 'unsupported' }
    }
    return @{performed=$true}
}
while ($null -ne ($line = [Console]::ReadLine())) {
    try {
        if ($line.Length -gt 65536) { throw 'unsupported' }
        $result = Request ($line | ConvertFrom-Json)
    } catch {
        $code = [string]$_.Exception.Message
        if ($code -notin 'stale','unsupported','capture') { $code = 'unavailable' }
        $result = @{error=$code}
    }
    [Console]::WriteLine(($result | ConvertTo-Json -Depth 12 -Compress))
}
