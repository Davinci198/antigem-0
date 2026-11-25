#!/bin/sh
# `pkg` inside AntiGem's container: apt-get, with the subcommands Termux users type.
# Written by the app; it translates the words and passes everything else straight through.
set -e
SUBCMD="${1:-help}"
[ $# -gt 0 ] && shift
case "$SUBCMD" in
  install|add|i)       exec apt-get install "$@" ;;
  uninstall|remove|rm) exec apt-get remove "$@" ;;
  purge)               exec apt-get purge "$@" ;;
  reinstall)           exec apt-get install --reinstall "$@" ;;
  update)              exec apt-get update "$@" ;;
  upgrade)             exec apt-get upgrade "$@" ;;
  full-upgrade)        exec apt-get dist-upgrade "$@" ;;
  search)              exec apt-cache search "$@" ;;
  show)                exec apt-cache show "$@" ;;
  list-all)            exec apt-cache search . ;;
  list-installed)      exec dpkg-query -W -f='${Package} ${Version}\n' ;;
  files)               exec dpkg-query -L "$@" ;;
  clean)               exec apt-get clean ;;
  autoclean)           exec apt-get autoclean ;;
  autoremove)          exec apt-get autoremove "$@" ;;
  help|-h|--help)
    echo "pkg: package management for AntiGem's container (apt-get underneath)"
    echo "  pkg install <name>     install a package"
    echo "  pkg uninstall <name>   remove it"
    echo "  pkg update             refresh the package lists"
    echo "  pkg upgrade            upgrade what is installed"
    echo "  pkg search <word>      search the archive"
    echo "  pkg list-installed     what is installed now"
    echo "  pkg files <name>       what a package put on disk"
    ;;
  *) exec apt-get "$SUBCMD" "$@" ;;
esac
