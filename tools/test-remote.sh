#!/bin/bash
# Manual smoke test of the encrypted protocol against a paired child's phone. Not part of `test` or
# `validate`: it needs a real phone, its pairing data in .secrets/devices/<slug>/, and network access, and
# grant/revoke change what the child can use.
#   [DEVICE=<slug>, default kid] tools/test-remote.sh status|grant <pkg> <min>|revoke <pkg>|read [since]
# A pass: `status` is followed, in `read`, by a decrypted status reply from the phone; after `grant`,
# the app can be opened on the phone and `read` shows the grant; after `revoke`, it can't. No reply within
# a minute (or a reply that doesn't decrypt) is a fail: check the pairing, the hub and the phone's network.
cd "$(dirname "$0")/.."
cli="java tools/ParentCli.java"
case "$1" in
  status) $cli send '{"t":"status","full":true}' ;;
  grant)  $cli send "{\"t\":\"grant\",\"app\":\"$2\",\"minutes\":${3:-2}}" ;;
  revoke) $cli send "{\"t\":\"revoke\",\"app\":\"$2\"}" ;;
  read)   $cli read "${2:-2m}" ;;
esac
