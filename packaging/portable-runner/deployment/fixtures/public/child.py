"""Public synthetic process fixture. Never a SUT or native execution proof."""
import os,subprocess,sys,time
mode=sys.argv[1]
if mode=='success':print('public success')
elif mode=='failure':sys.exit(7)
elif mode=='timeout':time.sleep(20)
elif mode=='overflow':os.write(1,b'x'*(1024*1024+1));time.sleep(20)
elif mode=='nested':
 subprocess.Popen([sys.executable,'-B',__file__,'timeout'],start_new_session=True)
 time.sleep(20)
else:raise SystemExit(2)
