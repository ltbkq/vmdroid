# build-rootfs/vsock-agent/Makefile
# musl-static aarch64 build. Invoked from Dockerfile.rootfs's vsock-builder stage.
CC      ?= gcc
# Hardening: the agent parses untrusted host-controlled vsock control lines.
# -fstack-protector-strong + FORTIFY are defense-in-depth (FORTIFY needs -O,
# present via -O2). Parsing is already bounds-correct; this is belt-and-braces.
CFLAGS  ?= -O2 -Wall -Wextra -Wno-unused-parameter -static -D_GNU_SOURCE \
           -fstack-protector-strong -D_FORTIFY_SOURCE=2
LDFLAGS ?=

BIN = podroid-vsock-agent

all: $(BIN)

$(BIN): podroid-vsock-agent.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

clean:
	rm -f $(BIN)

.PHONY: all clean
