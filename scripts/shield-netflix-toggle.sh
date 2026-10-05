#!/system/bin/sh
# Candidate for the existing Button 12 rule, NOT deployed by the offline build.
# Keep native PiP restoration and normal SmartTube commercial breaks intact.
if dumpsys activity activities | grep -q mode=pinned; then
    am start -n app.opentv/.MainActivity
elif dumpsys activity activities | grep 'mResumedActivity:' | grep -q 'app.opentv/.MainActivity'; then
    am broadcast --receiver-foreground -a app.opentv.action.NETFLIX_TOGGLE -p app.opentv
else
    am start -n app.smarttube.fdroid/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
fi
