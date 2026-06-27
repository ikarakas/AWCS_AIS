# AIS Relay Prototype — build & run
#
# Plain `make` lists targets. Override any variable on the command line, e.g.:
#   make recv-tcp PORT=5000 IDLE=10
#   make gws-serial DEVICE=/dev/ttys003

SHELL := /bin/bash
.DEFAULT_GOAL := help

# --- configuration (override on the command line) ---------------------------
# (keep comments on their own lines: trailing text after a value becomes part
#  of the variable in Make.)
# HOST   GWS host (TCP client / unicast dest)
# PORT   UDP/TCP port
# GROUP  multicast group
# IFACE  local interface IP for multicast (optional)
# IDLE   TCP receiver inactivity timeout (s; 0 = off)
# DEVICE serial device path for gws-serial
# DATA   feed file fed by serial-demo
HOST   ?= 127.0.0.1
PORT   ?= 4001
GROUP  ?= 239.192.0.1
IFACE  ?=
IDLE   ?= 15
DEVICE ?=
DATA   ?= data/sample_ais.nmea

JAVA  := java
JAVAC := javac
RECV  := mission-ada/bin/mission_receiver
GEN   := .run

.PHONY: help all build build-java build-ada clean kill \
        recv-udp recv-multicast recv-tcp \
        gws gws-tcp gws-serial gen-data serial-pty serial-demo

help: ## list targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-15s\033[0m %s\n",$$1,$$2}'

all: build

# --- build ------------------------------------------------------------------
build: build-java build-ada ## build Java GWS + Ada receiver

build-java: ## compile the Java GWS
	cd gws-java && mkdir -p out && $(JAVAC) -d out src/*.java

build-ada: ## build the Ada receiver
	cd mission-ada && gprbuild -P mission_receiver.gpr

clean: ## remove build artifacts and generated configs
	rm -rf gws-java/out $(GEN)
	-cd mission-ada && gprclean -P mission_receiver.gpr

# --- receiver (Ada) ---------------------------------------------------------
recv-udp: build-ada ## receiver: UDP unicast on PORT
	$(RECV) $(PORT)

recv-multicast: build-ada ## receiver: UDP join GROUP on PORT [IFACE]
	$(RECV) udp $(PORT) $(GROUP) $(IFACE)

recv-tcp: build-ada ## receiver: TCP client -> HOST:PORT with IDLE watchdog
	$(RECV) tcp $(HOST) $(PORT) $(IDLE)

# --- GWS (Java) -------------------------------------------------------------
$(GEN):
	mkdir -p $(GEN)

gws: build-java ## GWS using gws.properties as-is (UDP per the file)
	cd gws-java && $(JAVA) -cp out gws.GwsMain gws.properties

gws-tcp: build-java | $(GEN) ## GWS as a TCP server (overrides transport=tcp)
	sed 's/^mode.transport.*/mode.transport = tcp/' \
	    gws-java/gws.properties > $(GEN)/gws-tcp.properties
	cd gws-java && $(JAVA) -cp out gws.GwsMain ../$(GEN)/gws-tcp.properties

gws-serial: build-java | $(GEN) ## GWS reading serial DEVICE=/dev/ttysNNN
	@if [ -z "$(DEVICE)" ]; then \
	  echo "set DEVICE=/dev/ttysNNN  (run 'make serial-pty' to create a pair)"; exit 2; fi
	sed -e 's/^source.type.*/source.type = serial/' \
	    -e 's#^serial.device.*#serial.device = $(DEVICE)#' \
	    gws-java/gws.properties > $(GEN)/gws-serial.properties
	cd gws-java && $(JAVA) -cp out gws.GwsMain ../$(GEN)/gws-serial.properties

# --- test data --------------------------------------------------------------
gen-data: ## generate ~1h synthetic feed -> data/sim_1h_ais.nmea
	python3 tools/gen_ais.py --vessels 300 --duration 3600 --interval 10 \
	  --out data/sim_1h_ais.nmea

# --- serial simulation (macOS/Linux; needs socat) ---------------------------
serial-pty: ## create a virtual serial pair and print the two device paths
	@command -v socat >/dev/null || { echo "socat not found (brew install socat)"; exit 2; }
	@echo "Use one path as DEVICE for 'make gws-serial'; write NMEA to the other."
	socat -d -d pty,raw,echo=0 pty,raw,echo=0

serial-demo: build-java build-ada | $(GEN) ## full serial demo: socat -> GWS(serial) -> UDP -> Ada receiver, feeding DATA
	PORT=$(PORT) DATA=$(DATA) RECV=$(RECV) GEN=$(GEN) GWSDIR=gws-java bash tools/serial_sim.sh

kill: ## stop any running GWS / receiver / socat
	-pkill -f gws.GwsMain
	-pkill -f mission_receiver
	-pkill -f 'socat.*pty'
