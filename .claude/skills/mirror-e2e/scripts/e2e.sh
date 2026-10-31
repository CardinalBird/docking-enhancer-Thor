#!/system/bin/sh
# Control Switch — on-device e2e suite.
#
# Exercises the shipped daemon against synthetic controllers, so every run is identical and no
# physical pad has to stay awake. Run it from the host with:
#     adb shell sh /data/local/tmp/e2e.sh
#
# Scope note: this drives the DAEMON directly. The app's supervisor flows (dock gating, auto-start,
# settings) are covered separately in the skill's manual checklist, because they need the real
# no-root PServer path rather than a shell-spawned process.

MIRROR=/data/local/tmp/input_mirror
VPAD=/data/local/tmp/vgamepad
TMP=/data/local/tmp/e2e
STATE=$TMP/hidden.state
HEART=$TMP/heartbeat
LOG=$TMP/mirror.log

PASS=0
FAIL=0

ok()  { echo "  PASS  $1"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

expect_exists()    { if [ -e "$1" ]; then ok "$2"; else bad "$2  [falta $1]"; fi; }
expect_missing()   { if [ ! -e "$1" ]; then ok "$2"; else bad "$2  [todavia existe $1]"; fi; }
expect_contains()  { if grep -q "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3"; fi; }

wait_for_file() {
    i=0
    while [ ! -s "$1" ] && [ "$i" -lt 60 ]; do sleep 0.1; i=$((i + 1)); done
}

wait_for_gone() {
    # $1 = path, $2 = max tenths of a second
    i=0
    while [ -e "$1" ] && [ "$i" -lt "$2" ]; do sleep 0.1; i=$((i + 1)); done
}

mirror_pid() { pidof input_mirror 2>/dev/null; }
# Root when the firmware has su (Odin). The Thor ships none: the shell user is in the input group, so
# everything but unlinking /dev/input nodes (T2, T3, T5) still runs, and those report it.
if command -v su >/dev/null 2>&1; then
    as_root() { su -c "$1"; }
else
    as_root() { sh -c "$1"; }
    echo "  NOTE: no su on this device; hide/restore tests need root and will fail"
fi

stop_mirror() {
    P=$(mirror_pid)
    [ -n "$P" ] && as_root "kill -KILL $P" 2>/dev/null
    i=0
    while [ -n "$(mirror_pid)" ] && [ "$i" -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
}

# Start the daemon as root, detached. Root is test scaffolding only — the product itself reaches the
# same binary through PServerBinder with no root at all.
start_mirror() {
    as_root "$MIRROR $* > $LOG 2>&1 &"
    i=0
    while [ -z "$(mirror_pid)" ] && [ "$i" -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
}

# --- setup ---------------------------------------------------------------------------------------

echo "=== setup ==="

# The app's supervisor would fight the suite for the controllers, so park it first.
am force-stop com.odininputmirror 2>/dev/null
am force-stop com.controlswitch 2>/dev/null
stop_mirror

rm -rf $TMP
mkdir -p $TMP

# Test the binary the app actually ships, not a rebuild that might drift from it.
# Without su the app's private copy is unreadable; build-harness.sh pushes the same committed binary.
if ! as_root "cp /data/data/com.controlswitch/files/bin/input_mirror $MIRROR && chmod 755 $MIRROR" 2>/dev/null &&
    ! [ -x $MIRROR ]; then
    echo "  ABORT: no pude copiar el binario del daemon (esta instalada la app?)"
    exit 1
fi
echo "  daemon: $(as_root "$MIRROR" 2>&1 | head -n 1 | cut -c1-40)..."

SRCFIFO=$TMP/src.fifo
DSTFIFO=$TMP/dst.fifo
mkfifo $SRCFIFO $DSTFIFO

# Source keeps the Odin quirk vendor (0x2020): hide_node refuses anything else. The target takes the
# Xbox product id so the daemon's Nintendo face-button swap stays off and forwarding is 1:1.
$VPAD --name "E2E Source Pad" --vendor 0x2020 --product 0x0111 --path-file $TMP/src.path < $SRCFIFO &
exec 3> $SRCFIFO
$VPAD --name "E2E Target Pad" --vendor 0x2020 --product 0x0112 --path-file $TMP/dst.path < $DSTFIFO &
exec 4> $DSTFIFO

wait_for_file $TMP/src.path
wait_for_file $TMP/dst.path
SRC=$(cat $TMP/src.path)
DST=$(cat $TMP/dst.path)

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "  ABORT: no se crearon los pads sinteticos"
    exit 1
fi
echo "  source=$SRC  target=$DST"

# --- T1: forwarding fidelity ---------------------------------------------------------------------

echo ""
echo "=== T1: el daemon reenvia eventos al target ==="

start_mirror "$SRC" "$DST" --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco"
else
    ok "el daemon arranco (pid $(mirror_pid))"

    getevent -lt "$DST" > $TMP/fwd.txt 2>&1 &
    CAP=$!
    sleep 0.4

    echo "key 304 1" >&3; echo "syn" >&3
    echo "key 304 0" >&3; echo "syn" >&3
    echo "burst 40" >&3
    sleep 1.2
    kill $CAP 2>/dev/null

    # getevent labels 0x130 as BTN_GAMEPAD; BTN_SOUTH/BTN_A are aliases of the same code.
    expect_contains $TMP/fwd.txt "BTN_GAMEPAD" "el boton llega al target"
    expect_contains $TMP/fwd.txt "SYN_REPORT" "las tramas cierran con SYN_REPORT"

    ABS=$(grep -c "ABS_X" $TMP/fwd.txt 2>/dev/null)
    [ -z "$ABS" ] && ABS=0
    if [ "$ABS" -ge 30 ]; then
        ok "la rafaga llega completa (ABS_X x$ABS de 40)"
    else
        bad "la rafaga se perdio (ABS_X x$ABS, esperaba >=30)"
    fi
fi
stop_mirror

# --- T2: hide + restore --------------------------------------------------------------------------

echo ""
echo "=== T2: oculta el pad externo y lo devuelve al salir ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para ocultar"
else
    sleep 0.5
    expect_missing "$SRC" "el nodo del source desaparece de /dev/input"
    expect_exists  "$STATE" "queda registro de lo ocultado"

    # SIGTERM is the product's own stop signal; it must take the clean path that restores.
    as_root "kill -TERM $(mirror_pid)" 2>/dev/null
    stop_mirror
    sleep 0.5

    expect_exists  "$SRC" "el nodo vuelve tras una parada limpia"
    expect_missing "$STATE" "el registro se borra cuando restauro todo"
fi

# --- T3: orphan heal -----------------------------------------------------------------------------

echo ""
echo "=== T3: un daemon matado a lo bruto se puede sanar ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de huerfano"
else
    sleep 0.5
    as_root "kill -KILL $(mirror_pid)" 2>/dev/null
    sleep 0.5

    expect_missing "$SRC" "SIGKILL deja el nodo oculto (sin cleanup)"
    expect_exists  "$STATE" "el registro sobrevive para poder sanar"

    as_root "$MIRROR --heal --hidden-state-file $STATE" 2>/dev/null
    sleep 0.3

    expect_exists  "$SRC" "--heal devuelve el nodo huerfano"
    expect_missing "$STATE" "--heal limpia el registro"
fi

# --- T4: owner watchdog --------------------------------------------------------------------------

echo ""
echo "=== T4: el daemon no sobrevive a su app ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de watchdog"
else
    sleep 0.5
    expect_missing "$SRC" "el nodo esta oculto antes de la prueba"

    # Deleting the heartbeat is how the app signals "you have no owner": uninstalled, data cleared,
    # or a duplicate launch it abandoned.
    as_root "rm -f $HEART"
    wait_for_gone "/proc/$(mirror_pid)" 80

    if [ -z "$(mirror_pid)" ]; then
        ok "el daemon se apaga solo al perder su heartbeat"
    else
        bad "el daemon sigue vivo sin dueno"
        stop_mirror
    fi
    sleep 0.3
    expect_exists "$SRC" "y aun asi devuelve el nodo al salir"
fi

# --- T5: refuses to hide a node that is not the expected device ------------------------------------

echo ""
echo "=== T5: se niega a ocultar un dispositivo ajeno ==="

# Event numbers get recycled across reconnects, so the path the app resolved may point at something
# else by the time the daemon acts on it. Unlinking blindly there would take out an unrelated
# device; the daemon must check identity (EVIOCGID) and refuse.
#
# The decoy is --plain (a non-controller) rather than a foreign-vendor pad, because this handheld
# never lets a foreign-vendor CONTROLLER keep its node: the firmware republishes it as a 0x2020 twin
# and deletes the original, so there would be nothing left to test the refusal against.
FOREIGNFIFO=$TMP/foreign.fifo
mkfifo $FOREIGNFIFO
$VPAD --plain --name "E2E Decoy Device" --vendor 0x1234 --product 0x5678 --path-file $TMP/foreign.path < $FOREIGNFIFO &
exec 5> $FOREIGNFIFO
wait_for_file $TMP/foreign.path
FOREIGN=$(cat $TMP/foreign.path)

if [ -z "$FOREIGN" ] || [ ! -e "$FOREIGN" ]; then
    bad "se creo el senuelo y sobrevivio (sin el, el test no prueba nada)"
else
    ok "el senuelo existe antes de la prueba"
    start_mirror "$SRC" "$DST" --hide-node "$FOREIGN" --hidden-state-file $STATE --heartbeat-file $HEART
    sleep 0.6
    expect_exists "$FOREIGN" "el daemon NO desvincula un dispositivo ajeno"
    expect_contains $LOG "failed identity check" "y lo rechaza por identidad, no por accidente"
    stop_mirror
fi
exec 5>&-

# --- T6: hot reload ------------------------------------------------------------------------------

echo ""
echo "=== T6: cambiar un ajuste no reinicia el daemon ==="

CFG=$TMP/config.json
FIFO=$TMP/ctl
rm -f $CFG $FIFO
mkfifo $FIFO 2>/dev/null

generation() { sed -n 's/^generation //p' $HEART 2>/dev/null; }
write_config() {
    printf '{ "generation": %s, "home_mode": %s, "combo_hold_kill_app": false, "virtual_mouse": false }\n' \
        "$1" "$2" > $CFG.tmp
    mv $CFG.tmp $CFG
}
# El daemon mantiene el fifo abierto en O_RDWR, asi que siempre hay lector; el timeout esta para que
# un daemon muerto a mitad del test falle en vez de colgar la suite entera.
poke() { poke_cmd reload; }
# Cualquier comando del canal de control. El daemon mantiene el fifo abierto en O_RDWR, asi que
# siempre hay lector; el timeout esta para que un daemon muerto falle en vez de colgar la suite.
poke_cmd() { timeout 2 sh -c "echo '$1' > $FIFO"; }
# Una pulsacion completa en el pad sintetico de origen (fd 3 es su stdin).
press_source() { echo "key $1 1" >&3; echo "syn" >&3; echo "key $1 0" >&3; echo "syn" >&3; }
# Un eje del pad de origen a un valor concreto. Los ejes van de -32768 a 32767 con reposo en 0.
move_source() { echo "abs $1 $2" >&3; echo "syn" >&3; }

write_config 1 0
start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --hidden-state-file $STATE --heartbeat-file $HEART
PID_BEFORE=$(mirror_pid)
if [ -z "$PID_BEFORE" ]; then
    bad "el daemon arranco para el test de reload"
else
    sleep 1.2
    G=$(generation)
    if [ "$G" = "1" ]; then ok "adopta la config del arranque"; else bad "no publico la generacion inicial [$G]"; fi

    write_config 2 1
    poke
    sleep 1.5
    G=$(generation)
    if [ "$G" = "2" ]; then ok "adopta una config nueva sin reiniciar"; else bad "no adopto la generacion 2 [$G]"; fi

    echo '{ roto' > $CFG.tmp && mv $CFG.tmp $CFG
    poke
    sleep 1.5
    G=$(generation)
    if [ "$G" = "2" ]; then ok "rechaza una config rota y conserva la que corre"; else bad "adopto una config invalida [$G]"; fi
    expect_contains $LOG "keeping the running config" "y lo dice en el log en vez de fallar en silencio"

    # La asercion que le da sentido a todo el mecanismo: mismo proceso de punta a punta, o sea que el
    # grab exclusivo nunca se solto y el pad nunca se hizo visible.
    if [ "$(mirror_pid)" = "$PID_BEFORE" ]; then
        ok "el pid no cambio en ningun momento (no hubo restart)"
    else
        bad "el daemon se reinicio ($PID_BEFORE -> $(mirror_pid))"
    fi
    stop_mirror
fi
rm -f $CFG $FIFO

# --- T7: capture mode ----------------------------------------------------------------------------

echo ""
echo "=== T7: la captura reporta y no reenvia ==="

CAPLOG=$TMP/capture.log
rm -f $CAPLOG $CFG $FIFO
mkfifo $FIFO 2>/dev/null
write_config 1 0

start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --capture-file $CAPLOG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de captura"
else
    poke_cmd "capture button"
    sleep 0.5

    # Con la captura abierta, un boton tiene que aparecer en el log y NO llegar al target: apretar A
    # para mapearla no puede disparar A en lo que este en pantalla.
    timeout 3 getevent -lc 4 "$DST" > $TMP/t7.out 2>&1 &
    T7GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T7GE 2>/dev/null

    expect_contains $CAPLOG "button 304" "reporta el codigo apretado"
    if [ -s $TMP/t7.out ]; then
        bad "reenvio al target mientras capturaba"
    else
        ok "no reenvia nada mientras captura"
    fi

    # El latch: una segunda pulsacion no agrega otra linea hasta que se pida el paso siguiente.
    press_source 305
    sleep 1
    if [ "$(wc -l < $CAPLOG)" = "1" ]; then
        ok "el latch retiene hasta el proximo paso"
    else
        bad "el latch no retuvo ($(wc -l < $CAPLOG) lineas)"
    fi

    poke_cmd "capture off"
    sleep 0.5
    rm -f $TMP/t7.out
    timeout 3 getevent -lc 4 "$DST" > $TMP/t7.out 2>&1 &
    T7GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T7GE 2>/dev/null
    expect_contains $TMP/t7.out "BTN_GAMEPAD" "vuelve a reenviar al cerrar la captura"

    stop_mirror
fi
rm -f $CAPLOG $TMP/t7.out

# --- T8: el mapeo remapea ------------------------------------------------------------------------

echo ""
echo "=== T8: un mapeo en la config cambia el codigo reenviado ==="

# 304 (BTN_SOUTH) -> 307 (BTN_NORTH); 305 queda sin mapear y tiene que pasar intacto.
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_mode": 0,
  "combo_hold_kill_app": false,
  "virtual_mouse": false,
  "mapping": { "bindings": [[0, 304, 0, 0, 307, 0]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de mapeo"
else
    timeout 4 getevent -lc 8 "$DST" > $TMP/t8.out 2>&1 &
    T8GE=$!
    sleep 0.5
    press_source 304
    press_source 305
    sleep 2
    kill $T8GE 2>/dev/null

    expect_contains $TMP/t8.out "BTN_NORTH" "el codigo mapeado llega remapeado"
    expect_contains $TMP/t8.out "BTN_EAST" "el codigo sin mapear pasa intacto"
    # getevent nombra 0x130 como BTN_GAMEPAD; si aparece, el mapeo no se aplico.
    if grep -q "BTN_GAMEPAD" $TMP/t8.out 2>/dev/null; then
        bad "llego el codigo ORIGINAL: el mapeo no se aplico"
    else
        ok "el codigo original no llega"
    fi

    stop_mirror
fi
rm -f $TMP/t8.out $CFG $FIFO

# --- T9: un eje llena un slot de boton --------------------------------------------------------------

echo ""
echo "=== T9: media travesia de un eje llega como boton ==="

# Lo que la division vieja (una tabla de botones, otra de ejes, sin puente) no podia expresar: el
# gatillo de un pad que lo reporta como eje ocupando el slot de un boton.
# [kind=2 (media travesia), ABS_Z, +1] -> [kind=0 (boton), BTN_NORTH]
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_mode": 0,
  "combo_hold_kill_app": false,
  "virtual_mouse": false,
  "mapping": { "bindings": [[2, 2, 1, 0, 307, 0]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de eje-a-boton"
else
    # Primero por debajo del umbral: un gatillo apenas rozado no puede contar como apretado, o el
    # boton castanetea con el ruido del sensor.
    timeout 4 getevent -lc 6 "$DST" > $TMP/t9a.out 2>&1 &
    T9GE=$!
    sleep 0.5
    move_source 2 5000
    sleep 1.5
    kill $T9GE 2>/dev/null
    if grep -q "BTN_NORTH" $TMP/t9a.out 2>/dev/null; then
        bad "un roce del eje ya disparo el boton"
    else
        ok "por debajo del umbral no dispara"
    fi

    # Y ahora hasta el fondo.
    timeout 4 getevent -lc 8 "$DST" > $TMP/t9b.out 2>&1 &
    T9GE=$!
    sleep 0.5
    move_source 2 30000
    sleep 0.6
    move_source 2 0
    sleep 1.5
    kill $T9GE 2>/dev/null

    expect_contains $TMP/t9b.out "BTN_NORTH" "pasado el umbral llega como boton"
    # El evento de eje se consume: lo que el target ve es el boton, no las dos cosas.
    if grep -q "ABS_Z" $TMP/t9b.out 2>/dev/null; then
        bad "el eje original tambien llego al target"
    else
        ok "el eje original no llega"
    fi
    # Bajada y subida: sin la de vuelta el boton queda trabado apretado para siempre.
    if [ "$(grep -c "BTN_NORTH" $TMP/t9b.out 2>/dev/null)" = "2" ]; then
        ok "suelta el boton al volver el eje a reposo"
    else
        bad "no emitio el par apretar/soltar ($(grep -c "BTN_NORTH" $TMP/t9b.out 2>/dev/null) eventos)"
    fi

    stop_mirror
fi
rm -f $TMP/t9a.out $TMP/t9b.out $CFG

# --- T10: un boton llena una direccion de stick -----------------------------------------------------

echo ""
echo "=== T10: un boton llega como deflexion completa de un eje ==="

# El cruce inverso: [kind=0 (boton), BTN_SOUTH] -> [kind=2 (media travesia), ABS_RX, +1].
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_mode": 0,
  "combo_hold_kill_app": false,
  "virtual_mouse": false,
  "mapping": { "bindings": [[0, 304, 0, 2, 3, 1]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de boton-a-eje"
else
    timeout 4 getevent -lc 8 "$DST" > $TMP/t10.out 2>&1 &
    T10GE=$!
    sleep 0.5
    press_source 304
    sleep 1.5
    kill $T10GE 2>/dev/null

    # Una fuente digital no tiene medias tintas: da el tope del recorrido, 32767 = 0x7fff.
    expect_contains $TMP/t10.out "ABS_RX" "el boton llega como eje"
    expect_contains $TMP/t10.out "00007fff" "y con la deflexion al tope"
    # Y vuelve a reposo al soltar, o el stick queda clavado contra el borde.
    expect_contains $TMP/t10.out "00000000" "vuelve a reposo al soltar"
    if grep -q "BTN_GAMEPAD" $TMP/t10.out 2>/dev/null; then
        bad "el boton original tambien llego al target"
    else
        ok "el boton original no llega"
    fi

    stop_mirror
fi
rm -f $TMP/t10.out $CFG $FIFO

# --- T11: la captura no puede dejar el pad mudo -----------------------------------------------------

echo ""
echo "=== T11: una captura abandonada expira sola ==="

# Mientras hay un paso abierto el daemon no reenvia NADA. Si la app se cae ahi, el pad queda mudo y
# no hay forma de revivirlo desde el pad mismo — el timeout es la unica salida. Tarda sus 30s reales
# porque es lo que se esta probando; no hay forma honesta de acelerarlo.
rm -f $CFG $FIFO
mkfifo $FIFO 2>/dev/null
write_config 1 0

start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --capture-file $TMP/t11.cap --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de expiracion"
else
    poke_cmd "capture button"
    sleep 0.5

    # Confirmar primero que efectivamente esta mudo, o el test pasaria aunque la captura ni se abrio.
    timeout 3 getevent -lc 4 "$DST" > $TMP/t11a.out 2>&1 &
    T11GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T11GE 2>/dev/null
    if [ -s $TMP/t11a.out ]; then
        bad "la captura ni siquiera silencio el pad"
    else
        ok "con la captura abierta el pad esta mudo"
    fi

    echo "  (esperando los 30s del timeout...)"
    sleep 31

    timeout 4 getevent -lc 4 "$DST" > $TMP/t11b.out 2>&1 &
    T11GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T11GE 2>/dev/null
    expect_contains $TMP/t11b.out "BTN_GAMEPAD" "el pad revive solo sin que nadie cierre la captura"

    stop_mirror
fi
rm -f $TMP/t11.cap $TMP/t11a.out $TMP/t11b.out $CFG $FIFO

# --- T12: virtual mouse ----------------------------------------------------------------------------
#
# The one feature whose failure is silent: the pointer lives on its own uinput device, so a mistake
# here does not break forwarding, it just means Select+R3 does nothing — or worse, leaves a pointer
# behind that nothing will ever destroy. Asserts on the device's existence in /proc/bus/input/devices
# rather than on a log line, because that is what Android itself reacts to.

echo ""
echo "=== T12: el raton virtual aparece y se va con el combo ==="

# The pointer's own node, by the name mouse.c gives it. The handlers line reads
# "H: Handlers=event7 mouse2", so the prefix has to come off before the fields mean anything.
mouse_node() {
    awk '/Name="Control Switch Mouse"/{f=1}
         f && /Handlers=/ {
             sub(/.*Handlers=/, "")
             for (i = 1; i <= NF; i++) if ($i ~ /^event/) { print "/dev/input/" $i; exit }
         }' /proc/bus/input/devices 2>/dev/null
}

# Select+R3 has to be HELD past the toggle threshold (500ms); the daemon completes it on a poll
# timeout, so nothing new needs to arrive for it to fire.
hold_mouse_combo() {
    echo "key 314 1" >&3; echo "syn" >&3
    echo "key 318 1" >&3; echo "syn" >&3
    sleep 0.9
    echo "key 318 0" >&3; echo "syn" >&3
    echo "key 314 0" >&3; echo "syn" >&3
    echo "syn" >&3
    sleep 0.4
}

start_mirror "$SRC" "$DST" --virtual-mouse --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco en modo raton"
else
    if [ -z "$(mouse_node)" ]; then
        ok "sin el combo no hay puntero"
    else
        bad "sin el combo no hay puntero  [ya existe $(mouse_node)]"
    fi

    hold_mouse_combo
    MOUSE=$(mouse_node)

    if [ -n "$MOUSE" ]; then
        ok "el combo crea el puntero ($MOUSE)"

        getevent -lt "$MOUSE" > $TMP/t12.mouse 2>&1 &
        T12M=$!
        getevent -lt "$DST"   > $TMP/t12.dst   2>&1 &
        T12D=$!
        sleep 0.4

        # Stick deflection becomes cursor motion on the frame cadence, not per event.
        echo "abs 0 30000" >&3; echo "syn" >&3
        sleep 0.3
        echo "abs 0 -30000" >&3; echo "syn" >&3
        sleep 0.3
        echo "abs 0 0" >&3; echo "syn" >&3
        # A and B become the two clicks.
        echo "key 304 1" >&3; echo "syn" >&3
        sleep 0.2
        echo "key 304 0" >&3; echo "syn" >&3
        echo "key 305 1" >&3; echo "syn" >&3
        sleep 0.2
        echo "key 305 0" >&3; echo "syn" >&3
        sleep 0.5
        kill $T12M $T12D 2>/dev/null

        expect_contains $TMP/t12.mouse "REL_X" "el stick mueve el cursor"
        # getevent prints 0x110 as BTN_MOUSE, never BTN_LEFT — same code, different alias, exactly
        # like BTN_GAMEPAD for 0x130. Asserting on BTN_LEFT reads as a broken click that works fine.
        expect_contains $TMP/t12.mouse "BTN_MOUSE" "A hace click izquierdo"
        expect_contains $TMP/t12.mouse "BTN_RIGHT" "B hace click derecho"
        # The whole point of mouse mode: the game underneath stops receiving the pad.
        if grep -q "BTN_GAMEPAD" $TMP/t12.dst 2>/dev/null; then
            bad "el pad sigue llegando al target mientras el raton esta activo"
        else
            ok "el pad deja de llegar al target mientras el raton esta activo"
        fi

        # Leaving must hide the cursor WITHOUT touching the screen: the helper may hover (a tracking
        # id) but never press BTN_TOUCH, which is what used to tap (0,0) and dismiss open dialogs.
        TOUCHN=$(awk '/Name="Control Switch Mirror"/{f=1} f && /Handlers=/ { sub(/.*Handlers=/, ""); for (i = 1; i <= NF; i++) if ($i ~ /^event/) { print "/dev/input/" $i; exit } }' /proc/bus/input/devices 2>/dev/null)
        # Launched bare, not behind a `[ ] &&`: killing the pid of a backgrounded list kills the
        # subshell and orphans getevent, which then holds adb's output open and the run never returns.
        T12T=
        if [ -n "$TOUCHN" ]; then
            getevent -lt "$TOUCHN" > $TMP/t12.touch 2>&1 &
            T12T=$!
        fi
        sleep 0.3
        hold_mouse_combo
        sleep 0.3
        [ -n "$T12T" ] && kill $T12T 2>/dev/null
        expect_contains $TMP/t12.touch "ABS_MT_TRACKING_ID" "al salir pasa un hover por el ayudante tactil"
        if grep -q "BTN_TOUCH" $TMP/t12.touch 2>/dev/null; then
            bad "al salir toca la pantalla (BTN_TOUCH en el ayudante)"
        else
            ok "al salir no toca la pantalla"
        fi
        if [ -z "$(mouse_node)" ]; then
            ok "el combo se lleva el puntero"
        else
            bad "el combo se lleva el puntero  [sigue ahi $(mouse_node)]"
        fi

        # Forwarding has to come back, or the pad is left mute.
        getevent -lt "$DST" > $TMP/t12.back 2>&1 &
        T12B=$!
        sleep 0.4
        echo "key 304 1" >&3; echo "syn" >&3
        echo "key 304 0" >&3; echo "syn" >&3
        sleep 0.5
        kill $T12B 2>/dev/null
        expect_contains $TMP/t12.back "BTN_GAMEPAD" "el pad vuelve a llegar al target al salir"
    else
        bad "el combo crea el puntero"
    fi

    stop_mirror
    # Nothing may outlive the daemon: a stranded pointer would sit in the device list forever.
    if [ -z "$(mouse_node)" ]; then
        ok "no queda ningun puntero tras cerrar el daemon"
    else
        bad "quedo un puntero huerfano  [$(mouse_node)]"
    fi
fi
rm -f $TMP/t12.mouse $TMP/t12.dst $TMP/t12.back $TMP/t12.touch

# --- T13: un stick tocado al arrancar no deja deriva -------------------------------------------------

echo ""
echo "=== T13: un stick tocado al arrancar no queda descentrado ==="

# Reported from the field as drift to the right. The daemon used to take an axis's resting point
# from wherever it sat at startup, and it starts the moment a pad connects, i.e. in someone's hands.
# Hold the stick right at startup, let go, and the target must read centre, not an offset.
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_mode": 0,
  "combo_hold_kill_app": false,
  "virtual_mouse": false,
  "mapping": { "bindings": [[2, 0, 1, 2, 0, 1], [2, 0, -1, 2, 0, -1]] }
}
EOF
mv $CFG.tmp $CFG

move_source 0 12000
sleep 0.2
start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de deriva"
else
    timeout 3 getevent -lc 4 "$DST" > $TMP/t13.out 2>&1 &
    T13GE=$!
    sleep 0.5
    # Through the left first: the input core drops an event repeating the target's current value,
    # and the target already reads centre, so a correct release would otherwise send nothing at all.
    move_source 0 -6000
    sleep 0.2
    move_source 0 0
    sleep 1.5
    kill $T13GE 2>/dev/null
    LAST=$(grep "ABS_X" $TMP/t13.out | tail -n 1 | awk '{print $NF}')
    if [ "$LAST" = "00000000" ]; then
        ok "el stick suelto llega centrado"
    else
        bad "el stick suelto llega descentrado [$LAST]"
    fi
    stop_mirror
fi
rm -f $TMP/t13.out $CFG

# --- T17: un eje sin mapear se reescala al rango del target -----------------------------------------

echo ""
echo "=== T17: un eje sin mapear se reescala entre rangos distintos ==="

# A pad reporting its stick as 0..255 into a target expecting -32768..32767. Forwarded raw, full right
# arrived as 255 — a hair right of centre — and full left as 0, dead centre. Rescaled, the limits land
# on the limits and the source's rest (127) on the target's (0).
NARROWFIFO=$TMP/narrow.fifo
mkfifo $NARROWFIFO
$VPAD --name "E2E Narrow Pad" --vendor 0x2020 --product 0x0111 --range 0 255 --path-file $TMP/narrow.path < $NARROWFIFO &
exec 6> $NARROWFIFO
wait_for_file $TMP/narrow.path
NARROW=$(cat $TMP/narrow.path)

if [ -z "$NARROW" ] || [ ! -e "$NARROW" ]; then
    bad "se creo el pad de rango angosto"
else
    start_mirror "$NARROW" "$DST" --heartbeat-file $HEART
    if [ -z "$(mirror_pid)" ]; then
        bad "el daemon arranco para el test de reescalado"
    else
        getevent -lt "$DST" > $TMP/t17.out 2>&1 &
        T17GE=$!
        sleep 0.4
        echo "abs 0 255" >&6; echo "syn" >&6
        sleep 0.2
        echo "abs 0 0" >&6; echo "syn" >&6
        sleep 0.2
        echo "abs 0 127" >&6; echo "syn" >&6
        sleep 0.6
        kill $T17GE 2>/dev/null

        # getevent prints the value as 32-bit hex: 32767 = 00007fff, -32768 = ffff8000.
        expect_contains $TMP/t17.out "ABS_X.*00007fff" "el tope del source llega como tope del target"
        expect_contains $TMP/t17.out "ABS_X.*ffff8000" "el minimo del source llega como minimo del target"
        if grep -q "ABS_X.*000000ff" $TMP/t17.out 2>/dev/null; then
            bad "llego el valor crudo sin reescalar"
        else
            ok "el valor crudo no llega"
        fi
        LAST=$(grep "ABS_X" $TMP/t17.out | tail -n 1 | awk '{print $NF}')
        if [ "$LAST" = "00000000" ]; then
            ok "el reposo del source llega como reposo del target"
        else
            bad "el reposo del source llega descentrado [$LAST]"
        fi
        stop_mirror
    fi
fi
exec 6>&-
rm -f $TMP/t17.out $NARROWFIFO $TMP/narrow.path

# --- T18: nunca oculta el nodo del target -----------------------------------------------------------

echo ""
echo "=== T18: se niega a ocultar el propio target ==="

# On the Thor the built-in pad (the target) carries the same 0x2020 vendor as the external pad's twin,
# so the vendor check T5 covers would let a wrong hide path unlink the node the mirror writes into.
# Without su the unlink fails anyway, so the node surviving proves nothing there; the log line does.
start_mirror "$SRC" "$DST" --hide-node "$DST" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test del target"
else
    sleep 0.6
    expect_exists "$DST" "el daemon NO desvincula el nodo del target"
    expect_contains $LOG "is the mirror target" "y lo rechaza por ser el target"
    P=$(mirror_pid)
    as_root "kill -TERM $P" 2>/dev/null
    wait_for_gone "/proc/$P" 30
    stop_mirror
fi
# A daemon that did hide it put it back on SIGTERM; heal whatever a failure left behind regardless.
as_root "$MIRROR --heal --hidden-state-file $STATE" 2>/dev/null
rm -f $STATE
# --- T14: lo que queda apretado se suelta al parar ---------------------------------------------

echo ""
echo "=== T14: lo que queda apretado se suelta al parar ==="

# A stick deflected and a button down when the daemon stops (pad sleeping, undock) used to stay
# that way on the internal pad into handheld play. A clean stop must send the releases itself.
start_mirror "$SRC" "$DST" --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de suelta"
else
    getevent -lt "$DST" > $TMP/t14.out 2>&1 &
    T14GE=$!
    sleep 0.4
    echo "key 304 1" >&3; echo "syn" >&3
    move_source 0 12000
    sleep 0.3
    as_root "kill -TERM $(mirror_pid)" 2>/dev/null
    i=0; while [ -n "$(mirror_pid)" ] && [ "$i" -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
    sleep 0.3
    kill $T14GE 2>/dev/null
    LASTKEY=$(grep "BTN_GAMEPAD" $TMP/t14.out | tail -n 1 | awk '{print $NF}')
    LASTABS=$(grep "ABS_X" $TMP/t14.out | tail -n 1 | awk '{print $NF}')
    if [ "$LASTKEY" = "UP" ]; then ok "el boton se suelta al parar"; else bad "el boton queda apretado al parar [$LASTKEY]"; fi
    if [ "$LASTABS" = "00000000" ]; then ok "el stick vuelve al centro al parar"; else bad "el stick queda desviado al parar [$LASTABS]"; fi
    # Leave the source at rest for the tests that follow (the daemon is gone, so nothing forwards this).
    echo "key 304 0" >&3; echo "syn" >&3
    move_source 0 0
    sleep 0.2
fi
rm -f $TMP/t14.out

# --- T15: los comandos del fifo se aplican en orden -------------------------------------------

echo ""
echo "=== T15: los comandos del fifo se aplican en orden ==="

# Two commands in ONE write. The daemon used to match the whole buffer by priority, so this pair
# left the capture ON and the pad mute for 30 s; applied in order, the pad keeps forwarding.
# First a lone "capture button" must mute the pad, or a passing second half proves nothing.
rm -f $CAPLOG $CFG $FIFO
mkfifo $FIFO 2>/dev/null
write_config 1 0
start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --capture-file $CAPLOG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de orden"
else
    poke_cmd "capture button"
    sleep 0.5
    timeout 3 getevent -lc 4 "$DST" > $TMP/t15.out 2>&1 &
    T15GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T15GE 2>/dev/null
    if [ -s $TMP/t15.out ]; then bad "una captura sola no enmudece el pad (el test no prueba nada)"; else ok "una captura sola enmudece el pad"; fi
    poke_cmd "capture off"
    sleep 0.3

    # A shell builtin echo of a two-line string is ONE write(2); printf on this toybox is not.
    poke_cmd "capture button
capture off"
    sleep 0.5
    rm -f $TMP/t15.out
    timeout 3 getevent -lc 4 "$DST" > $TMP/t15.out 2>&1 &
    T15GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T15GE 2>/dev/null
    expect_contains $TMP/t15.out "BTN_GAMEPAD" "capture button + capture off en una escritura deja el pad vivo"
    stop_mirror
fi
rm -f $CAPLOG $TMP/t15.out

# --- T19: Select+L3 tocado es Home; Select+Start sostenido cierra la app ---------------------------

echo ""
echo "=== T19: Select+L3 tocado manda Home ==="

top_activity() { dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity; }
open_settings() { am start -n com.android.settings/.Settings >/dev/null 2>&1; sleep 2; }
# Select (314) + el otro boton, apretados juntos y soltados tras $2 segundos.
tap_with_select() {
    echo "key 314 1" >&3; echo "syn" >&3
    echo "key $1 1" >&3; echo "syn" >&3
    sleep $2
    echo "key $1 0" >&3; echo "syn" >&3
    echo "key 314 0" >&3; echo "syn" >&3
    sleep 2.5
}
in_settings() { top_activity | grep -q "com.android.settings"; }

# Sin la opcion el combo no hace nada; si esto falla, el positivo de abajo no prueba nada.
start_mirror "$SRC" "$DST" --heartbeat-file $HEART
open_settings
tap_with_select 317 0.2
if in_settings; then ok "sin --combo-tap-home Select+L3 no manda Home"; else bad "sin la opcion igual salio de Ajustes [$(top_activity)]"; fi
stop_mirror

start_mirror "$SRC" "$DST" --combo-tap-home --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de Select+L3"
else
    open_settings
    if in_settings; then ok "Ajustes en primer plano antes del combo"; else bad "no se pudo abrir Ajustes [$(top_activity)]"; fi
    # Select+Start queda para el atajo propio de Cocoon Shell: ya no es Home.
    tap_with_select 315 0.2
    if in_settings; then ok "Select+Start tocado ya no manda Home"; else bad "Select+Start todavia manda Home [$(top_activity)]"; fi
    open_settings
    getevent -lt "$DST" > $TMP/t19.out 2>&1 &
    T19GE=$!
    sleep 0.4
    tap_with_select 317 0.2
    kill $T19GE 2>/dev/null
    if in_settings; then bad "Select+L3 tocado no mando Home [$(top_activity)]"; else ok "Select+L3 tocado manda Home"; fi
    expect_contains $TMP/t19.out "BTN_SELECT" "Select sigue llegando al target"
    expect_contains $TMP/t19.out "BTN_THUMBL" "L3 sigue llegando al target"
    stop_mirror
fi
rm -f $TMP/t19.out

# El cierre por Select+Start sostenido 3 s sigue igual, y convive con Home en Select+L3.
start_mirror "$SRC" "$DST" --combo-hold-kill-app --combo-tap-home --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de cerrar app"
else
    open_settings
    tap_with_select 315 3.5
    if in_settings; then bad "Select+Start sostenido no cerro Ajustes [$(top_activity)]"; else ok "Select+Start sostenido 3 s cierra la app"; fi
    stop_mirror
fi

# --- T20: Home y Back como destino de un mapeo ----------------------------------------------------

echo ""
echo "=== T20: un boton mapeado a Home/Back hace Home/Back ==="

# 304 -> KEY_HOME (102), 305 -> KEY_BACK (158). Tocado sale ese codigo como un toque; sostenido
# (>= 500 ms) sale KEY_APPSELECT (Recientes). El boton original nunca llega.
cat > $CFG.tmp <<'JSON'
{
  "generation": 1,
  "home_mode": 0,
  "combo_hold_kill_app": false,
  "virtual_mouse": false,
  "mapping": { "bindings": [[0, 304, 0, 0, 102, 0], [0, 305, 0, 0, 158, 0]] }
}
JSON
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de Home/Back"
else
    open_settings
    getevent -lt "$DST" > $TMP/t20.out 2>&1 &
    T20GE=$!
    sleep 0.4
    press_source 304
    sleep 2
    if top_activity | grep -q "com.android.settings"; then bad "Home mapeado no salio de Ajustes [$(top_activity)]"; else ok "Home mapeado lleva al launcher"; fi
    press_source 305
    sleep 0.5
    echo "key 304 1" >&3; echo "syn" >&3
    sleep 0.8
    echo "key 304 0" >&3; echo "syn" >&3
    sleep 1
    kill $T20GE 2>/dev/null
    input keyevent 3

    N=$(grep -c "KEY_HOME" $TMP/t20.out 2>/dev/null)
    if [ "$N" = "2" ]; then ok "Home tocado llega como un toque de KEY_HOME"; else bad "KEY_HOME x$N, esperaba 2"; fi
    N=$(grep -c "KEY_BACK" $TMP/t20.out 2>/dev/null)
    if [ "$N" = "2" ]; then ok "Back tocado llega como un toque de KEY_BACK"; else bad "KEY_BACK x$N, esperaba 2"; fi
    N=$(grep -c "KEY_APPSELECT" $TMP/t20.out 2>/dev/null)
    if [ "$N" = "2" ]; then ok "Home sostenido llega como Recientes (KEY_APPSELECT)"; else bad "KEY_APPSELECT x$N, esperaba 2"; fi
    if grep -q -E "BTN_GAMEPAD|BTN_EAST" $TMP/t20.out 2>/dev/null; then bad "el boton original llego al target"; else ok "el boton original no llega"; fi
    stop_mirror
fi
rm -f $TMP/t20.out $CFG

# --- teardown ------------------------------------------------------------------------------------

echo ""
echo "=== teardown ==="
stop_mirror
exec 3>&-
exec 4>&-
sleep 0.5
as_root "rm -rf $TMP $MIRROR" 2>/dev/null
echo "  limpio"

echo ""
echo "=================================="
echo "  PASS: $PASS    FAIL: $FAIL"
echo "=================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
