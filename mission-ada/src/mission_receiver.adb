------------------------------------------------------------------------------
--  Mission_Receiver
--
--  Prototype receiver for AIS NMEA sentences. Mirrors the
--  Java GWS sender: one !AIVDM sentence per datagram (UDP) or one CRLF-framed
--  sentence per line (TCP), raw passthrough (no custom header on the wire).
--
--  Transports (chosen: "both, switchable"):
--    * UDP : bind the port, receive datagrams (unicast, or join a group).
--    * TCP : connect to the GWS TCP server and read the sentence stream.
--
--  Usage:
--    mission_receiver <port>                            (UDP unicast, legacy)
--    mission_receiver udp <port> [group] [iface_ip]     (UDP unicast/multicast)
--    mission_receiver tcp <host_ip> <port> [idle_secs]  (TCP client -> GWS)
--
--  For TCP, idle_secs (default 15, 0 disables) is an inactivity watchdog: if no
--  data arrives within that window the client assumes the link is dead (server
--  crash, cable pull, half-open connection) and reconnects.
--
--  Examples:
--    ./mission_receiver 4001
--    ./mission_receiver udp 4001 239.192.0.1
--    ./mission_receiver tcp 127.0.0.1 4001
--    ./mission_receiver tcp 127.0.0.1 4001 10
------------------------------------------------------------------------------

with Ada.Command_Line;        use Ada.Command_Line;
with Ada.Text_IO;             use Ada.Text_IO;
with Ada.Calendar;            use Ada.Calendar;
with Ada.Calendar.Formatting;
with Ada.Exceptions;          use Ada.Exceptions;
with Ada.Strings;             use Ada.Strings;
with Ada.Strings.Fixed;       use Ada.Strings.Fixed;
with Ada.Streams;             use Ada.Streams;
with GNAT.Sockets;            use GNAT.Sockets;

procedure Mission_Receiver is

   LF : constant Character := Character'Val (10);
   CR : constant Character := Character'Val (13);

   ---------------------------------------------------------------------------
   --  Convert received raw bytes to a String (sentence is ASCII).
   ---------------------------------------------------------------------------
   function To_String (Data : Stream_Element_Array;
                       Upto : Stream_Element_Offset) return String
   is
      Result : String (1 .. Natural (Upto));
   begin
      for I in 1 .. Upto loop
         Result (Natural (I)) := Character'Val (Natural (Data (I)));
      end loop;
      return Result;
   end To_String;

   function Port_Img (P : Port_Type) return String is
   begin
      return Trim (P'Image, Both);
   end Port_Img;

   procedure Print_Sentence (Count : Natural; Peer : String; S : String) is
   begin
      Put_Line (Ada.Calendar.Formatting.Image (Clock)
                & "  #" & Count'Image
                & "  [" & Peer & "]  " & S);
   end Print_Sentence;

   ---------------------------------------------------------------------------
   --  UDP receiver (unicast or multicast).
   ---------------------------------------------------------------------------
   procedure Run_Udp (Port      : Port_Type;
                      Multicast : Boolean;
                      Group     : Inet_Addr_Type;
                      Iface     : Inet_Addr_Type)
   is
      Server  : Socket_Type;
      Address : Sock_Addr_Type;
      From    : Sock_Addr_Type;
      Buffer  : Stream_Element_Array (1 .. 2048);
      Last    : Stream_Element_Offset;
      Count   : Natural := 0;
   begin
      Create_Socket (Server, Family_Inet, Socket_Datagram);
      Set_Socket_Option (Server, Socket_Level, (Reuse_Address, True));

      Address.Addr := Any_Inet_Addr;
      Address.Port := Port;
      Bind_Socket (Server, Address);

      if Multicast then
         Set_Socket_Option
           (Server, IP_Protocol_For_IP_Level, (Add_Membership, Group, Iface));
         Put_Line ("Joined multicast group " & Image (Group)
                   & " on port" & Port'Image);
      else
         Put_Line ("Listening (unicast) on port" & Port'Image);
      end if;

      Put_Line ("Waiting for AIS datagrams. Ctrl-C to stop.");
      Put_Line ("----------------------------------------------------------");

      loop
         Receive_Socket (Server, Buffer, Last, From);
         Count := Count + 1;
         Print_Sentence (Count, Image (From.Addr), To_String (Buffer, Last));
      end loop;
   end Run_Udp;

   ---------------------------------------------------------------------------
   --  TCP client: connect to the GWS server and read the CRLF-framed stream.
   --  Reconnects after a short delay if the connection drops.
   ---------------------------------------------------------------------------
   procedure Run_Tcp (Host : String; Port : Port_Type; Idle : Duration) is
      Sock      : Socket_Type;
      Addr      : Sock_Addr_Type;
      Buffer    : Stream_Element_Array (1 .. 2048);
      Last      : Stream_Element_Offset;
      Line      : String (1 .. 4096);
      Len       : Natural := 0;
      Count     : Natural := 0;
      Last_Data : Time;
      Peer      : constant String := Host & ":" & Port_Img (Port);
      Idle_S    : constant String := Trim (Integer'Image (Integer (Idle)), Both);
      --  Wake the blocking receive at least this often so the inactivity
      --  timeout can be enforced even when the link is silently dead.
      Poll      : constant Duration :=
        (if Idle < 2.0 and then Idle > 0.0 then Idle else 2.0);
   begin
      loop  --  reconnect loop
         begin
            Create_Socket (Sock, Family_Inet, Socket_Stream);
            --  OS-level dead-peer backstop, plus a receive timeout that turns
            --  the blocking read into a periodic poll for the idle watchdog.
            Set_Socket_Option (Sock, Socket_Level, (Keep_Alive, True));
            Set_Socket_Option (Sock, Socket_Level, (Receive_Timeout, Poll));
            Addr.Addr := Inet_Addr (Host);
            Addr.Port := Port;
            Put_Line ("Connecting (TCP) to " & Peer
                      & "  (idle timeout " & Idle_S & "s) ...");
            Connect_Socket (Sock, Addr);
            Last_Data := Clock;
            Put_Line ("Connected. Waiting for AIS sentences. Ctrl-C to stop.");
            Put_Line ("----------------------------------------------------------");

            loop
               declare
                  Timed_Out : Boolean := False;
               begin
                  begin
                     Receive_Socket (Sock, Buffer, Last);
                  exception
                     when Sock_E : Socket_Error =>
                        --  A receive timeout surfaces as this code; anything
                        --  else is a real error -> let the outer handler
                        --  reconnect.
                        if Resolve_Exception (Sock_E)
                             = Resource_Temporarily_Unavailable
                        then
                           Timed_Out := True;
                        else
                           raise;
                        end if;
                  end;

                  if Timed_Out then
                     if Idle > 0.0 and then Clock - Last_Data > Idle then
                        Put_Line ("[receiver] no data for >" & Idle_S
                                  & "s; assuming dead link, reconnecting ...");
                        exit;  --  leave inner loop -> reconnect
                     end if;
                  else
                     exit when Last < Buffer'First;  --  peer closed (FIN)
                     Last_Data := Clock;
                     for I in Buffer'First .. Last loop
                        declare
                           C : constant Character :=
                             Character'Val (Natural (Buffer (I)));
                        begin
                           if C = LF then
                              --  Only AIS sentences start with '!'. Anything
                              --  else (e.g. the $GWSHB heartbeat, blank lines)
                              --  still resets the idle timer but is not printed.
                              if Len > 0 and then Line (1) = '!' then
                                 Count := Count + 1;
                                 Print_Sentence (Count, Peer, Line (1 .. Len));
                              end if;
                              Len := 0;
                           elsif C = CR then
                              null;  --  drop CR; lines are framed on LF
                           elsif Len < Line'Last then
                              Len := Len + 1;
                              Line (Len) := C;
                           end if;
                        end;
                     end loop;
                  end if;
               end;
            end loop;

            Close_Socket (Sock);
            Put_Line ("[receiver] connection closed; reconnecting in 1s ...");
         exception
            when E : others =>
               Put_Line ("[receiver] TCP error: " & Exception_Message (E)
                         & "; retrying in 1s ...");
               begin
                  Close_Socket (Sock);
               exception
                  when others => null;
               end;
         end;
         Len := 0;
         delay 1.0;
      end loop;
   end Run_Tcp;

begin
   if Argument_Count < 1 then
      Put_Line ("Usage:");
      Put_Line ("  mission_receiver <port>                        (UDP unicast)");
      Put_Line ("  mission_receiver udp <port> [group] [iface_ip] (UDP)");
      Put_Line ("  mission_receiver tcp <host_ip> <port>          (TCP client)");
      Set_Exit_Status (Failure);
      return;
   end if;

   Initialize;

   declare
      A1 : constant String := Argument (1);
   begin
      if A1 = "tcp" then
         if Argument_Count < 3 then
            Put_Line ("Usage: mission_receiver tcp <host_ip> <port> [idle_secs]");
            Set_Exit_Status (Failure);
            return;
         end if;
         declare
            --  Idle timeout in seconds; 0 disables the watchdog.
            Idle : constant Duration :=
              (if Argument_Count >= 4 then Duration'Value (Argument (4))
               else 15.0);
         begin
            Run_Tcp (Argument (2), Port_Type'Value (Argument (3)), Idle);
         end;

      elsif A1 = "udp" then
         if Argument_Count < 2 then
            Put_Line ("Usage: mission_receiver udp <port> [group] [iface_ip]");
            Set_Exit_Status (Failure);
            return;
         end if;
         declare
            P  : constant Port_Type := Port_Type'Value (Argument (2));
            MC : constant Boolean   := Argument_Count >= 3;
            G  : constant Inet_Addr_Type :=
              (if MC then Inet_Addr (Argument (3)) else Any_Inet_Addr);
            IF_Addr : constant Inet_Addr_Type :=
              (if Argument_Count >= 4 then Inet_Addr (Argument (4))
               else Any_Inet_Addr);
         begin
            Run_Udp (P, MC, G, IF_Addr);
         end;

      else
         --  Legacy form: first argument is the UDP port.
         declare
            P  : constant Port_Type := Port_Type'Value (A1);
            MC : constant Boolean   := Argument_Count >= 2;
            G  : constant Inet_Addr_Type :=
              (if MC then Inet_Addr (Argument (2)) else Any_Inet_Addr);
            IF_Addr : constant Inet_Addr_Type :=
              (if Argument_Count >= 3 then Inet_Addr (Argument (3))
               else Any_Inet_Addr);
         begin
            Run_Udp (P, MC, G, IF_Addr);
         end;
      end if;
   end;

end Mission_Receiver;
