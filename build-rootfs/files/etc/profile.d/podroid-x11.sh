# Podroid X11 environment — sourced by /etc/profile for login shells.
# Apps launched from the user's shell inherit a working DISPLAY and
# PULSE_SERVER without any setup, so `xeyes` / `firefox` etc. just work.
#
# Only default it: a shell that already has a display of its own keeps it.
# `ssh -X` sets DISPLAY to its forwarding socket, and xrdp sources this file
# before starting a session, so overwriting it would send those sessions'
# windows to the in-app desktop and leave the remote screen black.
: "${DISPLAY:=:0}"
export DISPLAY

# PulseAudio: point clients (Firefox, mpv, etc.) at the native control
# socket exposed by module-native-protocol-unix in podroid-x11. The TCP
# 4713 port is the *capture* stream Android pulls — it's
# module-simple-protocol-tcp, not the native protocol, so Firefox can't
# talk to it. Without this, audio clients fail to find any sink and
# either play through a null device or fall back to noisy alsa dummies
# (the source of the clicking the user heard).
export XDG_RUNTIME_DIR=/run/podroid-pulse
export PULSE_SERVER=unix:/run/podroid-pulse/native
