# Advertise truecolor capability so apps (nvim, btop, lazygit, fzf) emit
# 24-bit color sequences. The TerminalEmulator already supports CSI 38;2;R;G;B
# and CSI 48;2;R;G;B; this just makes apps know they should use them.
export COLORTERM=truecolor
