@echo off
if not defined ADB set ADB=adb
if not defined VLC set VLC="C:\Program Files\VideoLAN\VLC\vlc.exe"
if not defined SNDCPY_APK set SNDCPY_APK=sndcpy.apk
if not defined SNDCPY_PORT set SNDCPY_PORT=28200

if not "%1"=="" (
    set serial=-s %1
    echo Waiting for device %1...
) else (
    echo Waiting for device...
)

%ADB% %serial% wait-for-device || goto :error

:checkIfAppIsInstalled
	%ADB% shell pm list packages | findstr /C:"com.rom1v.sndcpy" > temp.txt
	FindStr /C:"com.rom1v.sndcpy" temp.txt >Nul && (goto :startForwarding) || goto :installApp
:end

:installApp
	%ADB% %serial% install -t -r -g %SNDCPY_APK% || (
		echo Uninstalling existing version first...
		%ADB% %serial% uninstall com.rom1v.sndcpy || goto :error
		%ADB% %serial% install -t -g %SNDCPY_APK% || goto :error
	)
:end

:startForwarding
	%ADB% %serial% forward tcp:%SNDCPY_PORT% localabstract:sndcpy || goto :error
	%ADB% %serial% shell am start com.rom1v.sndcpy/.MainActivity || goto :error
:end

:: This method doesn't count that the screen could be skipped
:checkAllowed
	::Recommended delay time for the popup to appear
	timeout 5
	
	%ADB% shell dumpsys gfxinfo com.rom1v.sndcpy > temp.txt
	FindStr /C:"com.rom1v.sndcpy/com.rom1v.sndcpy.MainActivity/android.view.ViewRootImpl" temp.txt >Nul && (goto :checkAllowed) || goto :playAudio
:end

:playAudio
	echo Playing audio...
	%VLC% -Idummy --demux rawaud --network-caching=50 --play-and-exit tcp://localhost:%SNDCPY_PORT%
	goto :EOF
:end

:error
echo Failed with error #%errorlevel%.
pause
exit /b %errorlevel%