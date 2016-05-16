(ns dysentery.view
  "Provides a way to inspect DJ Link packets, watch for changing
  values, and develop an understanding of what they mean."
  (:require [dysentery.finder :as finder]
            [dysentery.util :as util]
            [dysentery.vcdj :as vcdj]
            [clojure.math.numeric-tower :as math]
            [selmer.parser :as parser]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Font]
           [java.awt.event WindowAdapter]
           [javax.swing JFrame JPanel JLabel SwingConstants]
           [java.net DatagramPacket DatagramSocket InetAddress]))

(def byte-font
  "The font to use for rendering byte values."
  (Font. "Monospaced" Font/PLAIN 14))

(def fade-time
  "The number of milliseconds over which the background of a label
  fades from blue to black after it has changed."
  1000)

(defonce ^{:doc "Set this atom to `true` if you want the program to exit
  when a packet window is closed."}
  exit-on-close-flag
  (atom false))

(defn exit-when-window-closed
  "Cause the program to exit when any packet capture window is
  closed."
  []
  (reset! exit-on-close-flag true))

(defonce ^{:private true
           :doc "A map whose keys are labels that have flashed blue
  because their value changed, and whose values are the time at which
  the value changed, so they can be faded back to black. Once they go
  black, they are removed from the map."}
  changed-labels (atom {}))

(defonce ^{:private true
           :doc "Holds the future which does the animation of label
  background colors so they can flash blue when changed and fade back
  to black."}
  animator (atom nil))

(defonce ^{:private true
           :doc "Holds a map of device numbers to the functions that
  should be called to update the corresponding informational frame
  when a packet is received from that device."}
  packet-frames (atom {}))

(def status-header
  "The bytes which are always expected at the beginning of a player
  status packet."
  [0x51 0x73 0x70 0x74 0x31 0x57 0x6d 0x4a 0x4f 0x4c])

(defn- correct-length?
  "Tests whether a packet has the length we are expecting. Logs a
  warning and returns nil otherwise."
  [packet expected-lengths]
  (or (expected-lengths (count packet))
      (timbre/warn "Expecting packet of length" expected-lengths "but it has length" (count packet))))

(defn- correct-type-and-length?
  "Tests whether a packet has the type value we are expecting, and the
  correct number of bytes for that length. Logs a warning and returns
  false otherwise."
  [packet expected-type]
  (let [current-type (get packet 10)]
    (if (= current-type expected-type)
      (case expected-type
        0x0a (correct-length? packet #{208 212})
        0x29 (correct-length? packet #{56})
        (timbre/warn "Received packet with unrecognized type:" current-type))
      (timbre/warn "Expecting packet of type" expected-type "but received type" current-type))))

(def ^:private mixer-unknown
  "The bytes which we think come at the end of a mixer status packet,
  so we can make them red if we find something unexpected. The device
  number bytes are left as zero because they have already been
  colored."
  [0 0 0 0x14 0 0 0 0xd0 0 0x10 0 0 0x80 0 0x2e 0xe0 0 0x10 0 0 0 9 0])

(defn recognized-if
  "If `recognized` is truthy, returns green, otherwise red."
  [recognized]
  (if recognized Color/green Color/red))

(defn- mixer-50002-byte-format
  "Given a packet which has been identified as coming from a mixer and a byte
  index that refers to one of the bytes not handled by [[byte-format]], see
  if it seems to be one that we expect, or something more surprising."
  [packet index value hex]
  (cond
    (#{46 47} index)  ; This is the current BPM
    [hex Color/green]

    (= index 54)  ; We don't know what this is, so far we have seen values 00 and FF
    [hex (recognized-if (#{0x00 0xff} value))]

    (= index 55)  ; We think this is a beat number ranging from 1 to 4
    [hex (recognized-if (<= 1 value 4))]

    :else
    [hex Color/white]))

(def ^:private cdj-unknown
  "Template for bytes in a CDJ packet which we don't yet have specific
  interpretations for, so we can at least color them red if they have
  a different value than we expect. Bytes with known interpretations
  are still present, but left as zero, to make it easier to index into
  the vector."
  [0x03 0x00 0x00 0xb0 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ;  32--47
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ;  48--63
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ;  64--79
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ;  80--95
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0x04 0x00 0x00 0x00 0x00  ;  96--111
   0x00 0x00 0x00 0x04 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 112--127
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x01 0x00 0x00 0xff 0x7e 0x00 0x00 0x00 0x00  ; 128--143
   0x00 0x00 0x00 0x00 0x7f 0xff 0xff 0xff 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0xff  ; 144--159
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 160--175
   0x00 0x00 0x00 0x00 0x00 0x00 0x10 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00  ; 176--191
   0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x00 0x0f 0x00 0x00 0x00  ; 192--207
   0x00 0x00 0x00 0x00])                                                            ; 208--211

(def mixer-status-flag-byte
  "The byte in packets that mixers send to port 50002 containing a set
  of status flag bits."
  39)

(def cdj-status-flag-byte
  "The byte in packets that CDJs send to port 50002 containing a set
  of status flag bits."
  137)

(def cdj-status-flag-playing-bit
  "The bit in the CDJ status flag byte which indicates it is currently
  in Play mode."
  2r01000000)

(def cdj-status-flag-master-bit
  "The bit in the CDJ status flag byte which indicates it is the master player."
  2r00100000)

(def cdj-status-flag-sync-bit
  "The bit in the CDJ status flag byte which indicates it is the master player."
  2r00010000)

(def cdj-status-flag-on-air-bit
  "The bit in the CDJ status flag byte which indicates it is on the
  air--that is, the mixer channel it is connected to is live and
  playing to the master output."
  2r00001000)

(defn build-int
  "Given a packet, the index of the first byte of an integer value,
  and the number of bytes that make it up, calculates the integer that
  the bytes represent."
  [packet index size]
  (loop [i (inc index)
         left (dec size)
         result (get packet index)]
    (if (pos? left)
      (recur (inc i) (dec left) (+ (* result 256) (get packet i)))
      result)))

(defn calculate-pitch
  "Given a packet and the index of the first byte of a pitch value,
  calculate the pitch shift percentage that it represents."
  [packet index]
  (* 100.0 (/ (- (build-int packet index 3) 0x100000)
              0x100000)))

(defn format-cue-countdown
  "Given a number of beats before a cue point, format it the way the
  CDJ does."
  [beats]
  (cond
    (= beats 0x1ff)
    "--.-"

    (<= 1 beats 0x100)
    (format "%02d.%d" (quot (dec beats) 4) (inc (rem (dec beats) 4)))

    (zero? beats)
    "00.0"

    :else
    "??.?"))

(defn- update-cdj-50002-details-label
 "Updates the label that gives a detailed explanation of how we
  interpret the status of a CDJ given a packet sent to port 50002 and
  the panel in which that packet is being shown."
  [packet label]
  (let [flag-bits (get packet cdj-status-flag-byte)
        track-bpm (/ (build-int packet 146 2) 100.0)
        no-track? (zero? (get packet 123))
        pitches (mapv (partial calculate-pitch packet) [141 153 193 197])
        cue-distance (build-int packet 164 2)
        raw-beat (build-int packet 160 4)
        beat (if (= raw-beat 0xffffffff) "n/a" raw-beat)
        args {:active (get packet 39)
              :track (build-int packet 50 2)
              :usb-activity (get packet 106)
              :usb-local (case (get packet 111)
                           4 "Unloaded"
                           2 "Unloading..."
                           0 "Loaded"
                           "???")
              :usb-global (if (pos? (get packet 117)) "Found" "Absent")
              :p-1 (case (get packet 123)
                     0 "No Track"
                     3 "Playing"
                     4 "Looping"
                     5 "Stopped"
                     6 "Cued"
                     9 "Search"
                     17 "Ended"
                     "???")
              :p-2 (case (get packet 139)
                     106 "Play"
                     110 "Stop"
                     122 "nxs Play"
                     126 "nxs Stop"
                     "???")
              :p-3 (case (get packet 157)
                     0 "No Track"
                     1 "Stop or Reverse"
                     9 "Forward Vinyl"
                     13 "Forward CDJ"
                     "???")

              :empty-f (zero? flag-bits)
              :playing-flag (pos? (bit-and flag-bits cdj-status-flag-playing-bit))
              :master-flag (pos? (bit-and flag-bits cdj-status-flag-master-bit))
              :sync-flag (pos? (bit-and flag-bits cdj-status-flag-sync-bit))
              :on-air-flag (pos? (bit-and flag-bits cdj-status-flag-on-air-bit))

              :sync-n (build-int packet 134 2)

              :bpm (if no-track? "---" (format "%.1f" track-bpm))
              :effective-bpm (if no-track? "---" (format "%.1f" (+ track-bpm (* track-bpm 1/100 (first pitches)))))
              :pitches (mapv (partial format "%+.2f%%") pitches)
              :beat beat
              :bar-beat (get packet 166)
              :bar-image (clojure.java.io/resource (str "images/Bar" (get packet 166) ".png"))
              :mem (format-cue-countdown cue-distance)
              :near-cue (< cue-distance 17)
              :packet (build-int packet 200 4)}]
    (.setText label (parser/render-file "templates/cdj-50002.tmpl" args)))
  label)

(def timestamp-formatter
  "Formats timestamps of incoming packets in the way we want them."
  (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss.SSS"))

(defn- create-timestamp-label
  "Creates labels that show when a packet was received."
  [panel]
  (let [label (JLabel. (.format timestamp-formatter (java.util.Date.)) SwingConstants/CENTER)]
    (.setFont label byte-font)
    (.setForeground label Color/yellow)
    (.setBackground label Color/black)
    (.setOpaque label true)
    (.add panel label)))

(defn- update-timestamp-label
  "Sets the label to show the current time and flashes its background
  blue."
  [label]
  (let [now (System/currentTimeMillis)]
    (.setText label (.format timestamp-formatter now))
    (.setBackground label Color/blue)
    (swap! changed-labels assoc label now)))

(defn- create-cdj-50002-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a CDJ given a packet sent to port 50002 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-cdj-50002-details-label packet label)))

(defn- update-mixer-50002-details-label
 "Updates the label that gives a detailed explanation of how we
  interpret the status of a mixer given a packet sent to port 50002
  and the panel in which that packet is being shown."
  [packet label]
  (let [flag-bits (get packet mixer-status-flag-byte)
        args {:bpm (format "%.1f" (/ (build-int packet 46 2) 100.0))
              :empty-f (zero? flag-bits)
              :playing-flag (pos? (bit-and flag-bits cdj-status-flag-playing-bit))
              :master-flag (pos? (bit-and flag-bits cdj-status-flag-master-bit))
              :sync-flag (pos? (bit-and flag-bits cdj-status-flag-sync-bit))
              :bar-beat (get packet 55)
              :bar-image (clojure.java.io/resource (str "images/Bar" (get packet 55) ".png"))}]
    (.setText label (parser/render-file "templates/mixer-50002.tmpl" args)))
  label)

(defn- create-mixer-50002-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a mixer given a packet sent to port 50002 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-mixer-50002-details-label packet label)))

(defn- cdj-50002-byte-format
  "Given a packet to port 50002 which has been identified as coming
  from a CDJ and a byte index that refers to one of the bytes not
  handled by [[byte-format]], see if it seems to be one that we
  expect, or something more surprising."
  [packet index value hex]
  (cond
    (= index 39)  ; Seems to be a binary activity flag?
    [hex (recognized-if (#{0 1} value))]

    (#{50 51} index)  ; Track index
    [hex (Color/green)]  ; All values are valid

    (= index 106)  ; USB actvity? Alternates between 4 and 6 when USB in use.
    [hex (recognized-if (#{4 6} value))]

    (= index 111)  ; Seems to indicate USB media status in the current player?
    [hex (recognized-if (#{0 2 4} value))] ; 4=no USB, 0=USB mounted, 2=unmounting USB

    (= index 117)  ; Seems to indicate USB media is mounted in any player?
    [hex (recognized-if (or (and (zero? value) (= 4 (get packet 111)))
                            (pos? value)))]

    (= index 123)  ; Play mode part 1
    [hex (recognized-if (#{0 3 4 5 6 9 0x11} value))]

    (<= 124 index 127)  ; Firmware version, in ascii
    [(if (pos? value) (str (char value)) "") Color/green]

    (#{134 135} index)  ; Sync counter
    [hex (Color/green)]  ; All values are valid

    (= index 137)  ; State flags
    [hex (recognized-if (or (= (bit-and value 2r10000111) 2r10000100)
                            (and (zero? value) (= 208 (count packet)))))]

    (= index 139)  ; Play mode part 2?
    [hex (recognized-if (#{0x6a 0x7a 0x6e 0x7e} value))]

    (#{141 153 193 197} index)  ; The first byte of the four pitch copies
    [hex (recognized-if (< value 0x21))] ; Valid pitces range from 0x000000 to 0x200000

    (#{142 143 154 155 194 195 198 199} index)  ; Later bytes of the four pitch copies
    [hex (Color/green)]  ; All values are valid

    (= 144 index)  ; First byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0x7f) (= (get packet (inc index)) 0xff))
                            (and (#{0x00 0x80} value) (= (get packet (inc index)) 0x00))))]
    
    (= 145 index)  ; Second byte of some kind of loaded indicator?
    [hex (recognized-if (or (and (= value 0xff) (= (get packet (dec index)) 0x7f))
                            (and (= value 0x00) (#{0x00 0x80} (get packet (dec index))))))]
    
    (#{146 147} index)  ; The BPM
    [hex (Color/green)]  ; All values are valid for now

    (= index 157)  ; Play mode part 3?
    [hex (recognized-if (#{0 1 9 13} value))]

    ;; The logic below turned out to be wrong; we have no idea what byte 158 is yet, but it is clearly up
    ;; to something.
    #_(= 158 index)  ; A combined loaded/master flag? 0=unloaded, 1=loaded but not master, 2=loaded and master
    #_[hex (recognized-if (or (and (zero? value) (zero? (get packet 123)))
                            (and (= 1 value) (pos? (get packet 123))
                                 (zero? (bit-and cdj-status-flag-master-bit (get packet cdj-status-flag-byte))))
                            (and (= 2 value) (pos? (get packet 123))
                                 (pos? (bit-and cdj-status-flag-master-bit (get packet cdj-status-flag-byte))))))]

    (<= 160 index 163)  ; The beat number within the track
    [hex (Color/green)]

    (= index 164)  ; First byte of countdown to next memory point; always 0 or 1
    [hex (recognized-if (#{0 1} value))]

    (= index 165)  ; Second byte of countdown to next memory point
    [hex (Color/green)]

    (= index 166)  ; We think this is a beat number ranging from 1 to 4, when analyzed track loaded.
    [hex (recognized-if (or (and (zero? value) (or (every? #(= 0xff %) (subvec packet 160 164))))
                            (and (<= 1 value 4) (some? #(not= 0xff %) (subvec packet 160 164)))))]

    (<= 200 index 203)  ; Packet counter
    [hex (Color/green)]

    (= index 204)  ; Seems to be 0x0f for nexus players, 0x05 for others?
    [hex (recognized-if (or (and (= value 0x0f) (= 212 (count packet)))
                            (and (= value 0x05) (= 208 (count packet)))))]

    :else
    [hex Color/white]))

(defn- packet-50002-byte-format
  "Given a device number, expected packet type, packet, and byte
  index, return the text and color that should be used to represent
  that byte in the packet based on our expectations."
  [device-number expected-type packet index]
  (let [current (get packet index)
        hex (format "%02x" current)]
    (cond
      (< index 10)  ; The standard header
      [hex (if (= current (get status-header index)) Color/green Color/red)]

      (= index 10)  ; The packet type
      [hex (if (correct-type-and-length? packet expected-type) Color/green Color/red)]

      (< index 31)  ; The device name
      [(if (pos? current) (str (char current)) "") Color/green]

      (= index 31)  ; We don't know what this byte is but expect it to be 1
      [hex (if (= current 1) Color/green Color/red)]

      (#{33 36} index)  ; We expect these bytes to match the device number
      [hex (if (= current device-number) Color/green Color/red)]

      (= expected-type 0x29)
      (mixer-50002-byte-format packet index current hex)

      (= expected-type 0x0a)
      (cdj-50002-byte-format packet index current hex)

      :else
      [hex Color/white])))

(defn- packet-50001-byte-format
  "Given a device number, packet, and byte index, return the text and
  color that should be used to represent that byte in the packet based
  on our expectations."
  [device-number packet index]
  (let [current (get packet index)
        hex (format "%02x" current)]
    (cond
      (< index 10)  ; The standard header
      [hex (if (= current (get status-header index)) Color/green Color/red)]

      (= index 10)  ; The packet type; seems to always be 28 for these
      [hex (if (= current 0x28) Color/green Color/red)]

      (< index 31)  ; The device name
      [(if (pos? current) (str (char current)) "") Color/green]

      (= index 31)  ; We don't know what this byte is but expect it to be 1
      [hex (if (= current 1) Color/green Color/red)]

      (#{33 95} index)  ; We expect these to match the device number
      [hex (if (= current device-number) Color/green Color/red)]

      (= index 85)  ; The first byte of the pitch
      [hex (recognized-if (< current 0x21))] ; Valid pitces range from 0x000000 to 0x200000

      (#{86 87} index)  ; Later bytes of the pitch
      [hex (Color/green)]  ; All values are valid

      (#{90 91} index)  ; This is the current BPM
      [hex Color/green]

      (= index 92)  ; We think this is a beat number ranging from 1 to 4
      [hex (recognized-if (<= 1 current 4))]

      :else
      [hex Color/white])))

(defn- create-byte-labels
  "Create a set of labels which will display the content of the
  packet, byte by byte, given the packet and a function which knows
  how to format each packet byte."
  [packet byte-formatter]
  (vec (for [index (range (count packet))]
         (let [[value color] (byte-formatter packet index)
               label (JLabel. value SwingConstants/CENTER)]
           (.setForeground label color)
           label))))

(defn- update-byte-labels
  "Update the content of the labels analyzing the packet when a new
  packet has been received."
  [packet byte-labels byte-formatter]
  (dotimes [index (count packet)]  ; We have a packet to update our state with
    (let [label (get byte-labels index)
          [value color] (byte-formatter packet index)]
      (when label
        (.setForeground label color)
        (when-not (.equals value (.getText label))
          ;; The value has changed, update the label and set the background bright blue, setting up a fade
          (.setText label value)
          (.setBackground label (Color/blue))
          (swap! changed-labels assoc label (System/currentTimeMillis)))))))

(defn- create-address-labels
  "Create a set of fixed labels which identify the addresses of the
  bytes shown, given the panel into which they should be installed,
  and the length of the packet."
  [panel packet-length]
  (doseq [x (range 16)]
    (let [label (JLabel. (format "%x" x) SwingConstants/CENTER)]
      (.setFont label byte-font)
      (.setForeground label Color/yellow)
      (.add panel label)
      (.setBounds label (* (inc x) 25) 0 20 15)))
  (doseq [y (range (inc (quot (dec packet-length) 16)))]
    (let [label (JLabel. (format "%x" y) SwingConstants/RIGHT)]
      (.setFont label byte-font)
      (.setForeground label Color/yellow)
      (.add panel label)
      (.setBounds label 0 (* (inc y) 15) 20 15))))

(defn- position-byte-labels
  "Place the labels which will display individual packet bytes at the
  proper locations within the panel that will hold them."
  [byte-labels panel]
  (loop [labels byte-labels
         index 0]
    (when (seq labels)
      (let [label (first labels)
            x (rem index 16)
            y (quot index 16)
            left (* x 20)
            top (* y 14)]
        (.setFont label byte-font)
        (.setForeground label Color/white)
        (.setBackground label Color/black)
        (.setOpaque label true)
        (.add panel label)
        (.setBounds label (* (inc x) 25) (* (inc y) 15) 20 15))
      (recur (rest labels) (inc index)))))

(defn- create-player-50002-frame
  "Creates a frame for displaying packets sent by the specified device
  number to port 50002, and returns a function to be called to update
  the frame when a new packet is received for that device."
  [device-number packet]
  (let [original-packet-type (get packet 10)
        frame (JFrame. (str "Player " device-number ", port 50002"))
        panel (JPanel.)
        byte-labels (create-byte-labels packet (partial packet-50002-byte-format device-number original-packet-type))
        timestamp-label (create-timestamp-label panel)
        details-label (case original-packet-type
                        0x0a (create-cdj-50002-details-label packet panel)
                        0x29 (create-mixer-50002-details-label packet panel)
                        nil)]
    (.setLayout panel nil)
    (.setSize frame 440 (if (= original-packet-type 0x0a) 470 220))
    (.setContentPane frame panel)
    (.setBackground panel Color/black)
    (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
    (.addWindowListener frame (proxy [WindowAdapter] []
                                (windowClosed [_]
                                  (swap! packet-frames dissoc [50002 device-number])
                                  (when @exit-on-close-flag
                                    (System/exit 0)))))

    (create-address-labels panel (count packet))
    (position-byte-labels byte-labels panel)
    (.setBounds timestamp-label 0 (case original-packet-type
                                    0x0a 226
                                    0x29 90)
                440 18)
    (when details-label (.setBounds details-label 0 (case original-packet-type
                                                      0x0a 255
                                                      0x29 120)
                                    440 200))

    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location)
      (.setVisible frame true)
      (fn [packet]
        (if (nil? packet)
          (do  ; We were told to shut down
            (.setVisible frame false)
            (.dispose frame))
          (do  ; We have an actual packet to display
            (update-timestamp-label timestamp-label)
            (update-byte-labels packet byte-labels
                                (partial packet-50002-byte-format device-number original-packet-type))
            (when details-label
              (case original-packet-type
                0x0a (update-cdj-50002-details-label packet details-label)
                0x29 (update-mixer-50002-details-label packet details-label)))))))))

(defn- update-player-50001-details-label
  "Updates the label that gives a detailed explanation of how we
  interpret the status of a player given a packet sent to port 50001
  and the panel in which that packet is being shown."
  [packet label]
  (let [pitch (calculate-pitch packet 85)
        track-bpm (/ (build-int packet 90 2) 100.0)
        args {:bpm (format "%.1f" track-bpm)
              :effective-bpm (format "%.1f" (+ track-bpm (* track-bpm 1/100 pitch)))
              :pitch (format "%+.2f%%" pitch)
              :bar-beat (get packet 92)
              :bar-image (clojure.java.io/resource (str "images/Bar" (get packet 92) ".png"))}]
    (.setText label (parser/render-file "templates/player-50001.tmpl" args)))
  label)

(defn- create-player-50001-details-label
  "Creates labels that give a detailed explanation of how we interpret
  the status of a player given a packet sent to port 50001 and the panel
  in which that packet is being shown."
  [packet panel]
  (let [label (JLabel. "" SwingConstants/CENTER)]
    (.setVerticalAlignment label SwingConstants/TOP)
    (.add panel label)
    (update-player-50001-details-label packet label)))

(defn- create-player-50001-frame
  "Creates a frame for displaying packets sent by the specified device
  number to port 50001, and returns a function to be called to update
  the frame when a new packet is received for that device."
  [device-number packet]
  (let [original-packet-type (get packet 10)
        frame (JFrame. (str "Player " device-number ", port 50001"))
        panel (JPanel.)
        byte-labels (create-byte-labels packet (partial packet-50001-byte-format device-number))
        timestamp-label (create-timestamp-label panel)
        details-label (create-player-50001-details-label packet panel)]
    (.setLayout panel nil)
    (.setSize frame 440 240)
    (.setContentPane frame panel)
    (.setBackground panel Color/black)
    (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
    (.addWindowListener frame (proxy [WindowAdapter] []
                                (windowClosed [_]
                                  (swap! packet-frames dissoc [50001  device-number])
                                  (when @exit-on-close-flag
                                    (System/exit 0)))))

    (create-address-labels panel (count packet))
    (position-byte-labels byte-labels panel)
    (.setBounds timestamp-label 0 115 440 18)
    (.setBounds details-label 0 145 440 200)

    (let [location (.getLocation frame)
          offset (* 20 (inc (count @packet-frames)))]
      (.translate location offset offset)
      (.setLocation frame location)
      (.setVisible frame true)
      (fn [packet]
        (if (nil? packet)
          (do  ; We were told to shut down
            (.setVisible frame false)
            (.dispose frame))
          (do  ; We have an actual packet to display
            (update-timestamp-label timestamp-label)
            (update-byte-labels packet byte-labels
                                (partial packet-50001-byte-format device-number))
            (update-player-50001-details-label packet details-label)))))))

(defn- create-player-frame
  "Creates a frame for displaying packets sent to the specified port
  by the specified device number, and returns a function to be called
  to update the frame when a new packet is received on that port for
  that device."
  [port device-number packet]
  (case port
    50001 (create-player-50001-frame device-number packet)
    50002 (create-player-50002-frame device-number packet)))

(defn- handle-device-packet
  "Find and update or create the frame used to display packets on the
  specified port from the specified device."
  [port device-number packet]
  (if-let [frame (get @packet-frames [port device-number])]
    (frame packet)
    (swap! packet-frames assoc [port device-number] (create-player-frame port device-number packet))))

(defonce ^{:private true
           :doc "Holds the persistent server socket for receiving
  packets broadcast to port 50001 and the future that processes
  packets."}
  watcher-state (atom {:socket nil
                       :watcher nil}))

(defn stop-watching-devices
  "Remove the packet listener, close the frames, and shut down the
  virtual CDJ."
  []
  (vcdj/remove-packet-listener handle-device-packet)
  (vcdj/shut-down)
  (swap! watcher-state (fn [current]
                         (-> current
                             (update-in [:socket] #(when %
                                                     (try (.close %)
                                                          (catch Exception e
                                                            (timbre/warn e "Problem closing port 50001 socket.")))
                                                     nil))
                             (update-in [:watcher] #(when %
                                                      (try (future-cancel %)
                                                           (catch Exception e
                                                             (timbre/warn e "Problem stopping port 50001 watcher.")))
                                                      nil)))))
  (doseq [frame (vals @packet-frames)]
    (frame nil))
  (reset! packet-frames {}))

(defn- receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload packet."
  [^DatagramSocket socket]
  (let [buffer (byte-array 512)
        packet (DatagramPacket. buffer 512)]
    (try (.receive socket packet)
         packet
         (catch Exception e
           (timbre/error e "Problem reading from DJ Link socket, shutting down.")
           (stop-watching-devices)))))

(defn- start-watching-devices
  "Once we have found some DJ-Link devices, set up a virtual CDJ to
  receive packets on port 50002 from them, create our own socket to
  monitor packets broadcast to port 50001, and register packet
  listeners that will create or update windows to display those
  packets."
  [devices]
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (vcdj/start interface address)
    (vcdj/add-packet-listener (partial handle-device-packet 50002)))
  (try
    (swap! watcher-state
           (fn [current]
             (let [socket (DatagramSocket. 50001 (InetAddress/getByName "0.0.0.0"))]
               (-> current
                   (assoc :socket socket)
                   (assoc :watcher
                          (future (loop []
                                    (let [packet (receive socket)
                                          data (vec (map util/unsign (take (.getLength packet) (.getData packet))))]
                                      ;; Check packet length; we only want ones with interesting data
                                      (when (= 96 (.getLength packet))
                                        (handle-device-packet 50001 (get data 33) data)))
                                    (recur))))))))
    (catch Exception e
      (timbre/warn e "Failed while trying to set up DJ-Link reception.")
      (stop-watching-devices))))

(defn describe-devices
  "Print a descrption of the DJ Link devices found, and how to
  interact with them."
  [devices]
  (println "Found:")
  (doseq [device devices]
    (println "  " (:name device) (.toString (:address device))))
  (println)
  (let [[interface address] (finder/find-interface-and-address-for-device (first devices))]
    (println "To communicate create a virtual CDJ with address" (str (.toString (.getAddress address)) ","))
    (print "MAC address" (clojure.string/join ":" (map (partial format "%02x")
                                                       (map util/unsign (.getHardwareAddress interface)))))
    (println ", and use broadcast address" (.toString (.getBroadcast address)))))

(defn find-devices
  "Run a loop that waits a few seconds to see if any DJ Link devices
  can be found on the network. If so, describe them and how to reach
  them."
  []
  (println "Looking for DJ Link devices...")
  (finder/start-if-needed)
  (Thread/sleep 2000)
  (loop [devices (finder/current-dj-link-devices)
         tries 3]
    (if (seq devices)
      devices
      (if (zero? tries)
        (println "No DJ Link devices found; giving up.")
        (do
          (Thread/sleep 1000)
          (recur (finder/current-dj-link-devices) (dec tries)))))))

(defn watch-devices
  "Create windows that show packets coming from all DJ-Link devices on
  the network, to help analyze them."
  []
  (when-let [devices (seq (find-devices))]
    (describe-devices devices)
    (swap! animator (fn [current]
                      (or current
                          (future (loop [now (System/currentTimeMillis)]
                                    (doseq [[label changed] @changed-labels]
                                      (let [age (- now changed)]
                                        (if (> age fade-time)
                                          (do
                                            (.setBackground label Color/black)
                                            (swap! changed-labels dissoc label))
                                          (.setBackground label (Color. (int 0) (int 0)
                                                                        (int (math/round (* 255 (/ (- fade-time age)
                                                                                                   fade-time)))))))))
                                    (Thread/sleep 50)
                                    (recur (System/currentTimeMillis)))))))
    (start-watching-devices devices)))
