"""Independent primitive encoder, Keccak-f and RFC6979 neutral signer.
No Sigilaris classes, Scala codec output, filesystem fixture data or crypto package
is imported. Byte order follows the reviewed P0/P5 product schema. This program
is a validation aid; its literals are fixed in the public Scala source.
"""
from pathlib import Path
import hashlib,hmac,json,re
MASK=(1<<64)-1
RC=[0x1,0x8082,0x800000000000808a,0x8000000080008000,0x808b,0x80000001,0x8000000080008081,0x8000000000008009,0x8a,0x88,0x80008009,0x8000000a,0x8000808b,0x800000000000008b,0x8000000000008089,0x8000000000008003,0x8000000000008002,0x8000000000000080,0x800a,0x800000008000000a,0x8000000080008081,0x8000000000008080,0x80000001,0x8000000080008008]
ROT=[[0,36,3,41,18],[1,44,10,45,2],[62,6,43,15,61],[28,55,25,21,56],[27,20,39,8,14]]
def rol(x,n):return ((x<<n)|(x>>(64-n)))&MASK
def keccak(data):
 padding=136-(len(data)%136)
 data+=b'\x81' if padding==1 else b'\x01'+b'\0'*(padding-2)+b'\x80'
 a=[0]*25
 for off in range(0,len(data),136):
  for i in range(17):a[i]^=int.from_bytes(data[off+8*i:off+8*i+8],'little')
  for rc in RC:
   c=[a[x]^a[x+5]^a[x+10]^a[x+15]^a[x+20] for x in range(5)]
   d=[c[(x-1)%5]^rol(c[(x+1)%5],1) for x in range(5)]
   for i in range(25):a[i]^=d[i%5]
   b=[0]*25
   for x in range(5):
    for y in range(5):b[y+5*((2*x+3*y)%5)]=rol(a[x+5*y],ROT[x][y])
   for x in range(5):
    for y in range(5):a[x+5*y]=b[x+5*y]^((~b[(x+1)%5+5*y])&b[(x+2)%5+5*y])
   a[0]^=rc
 return b''.join(v.to_bytes(8,'little') for v in a)[:32]
assert keccak(b'').hex()=='c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470'
assert keccak(b'abc').hex()=='4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45'
def nat(n):
 assert n>=0
 if n<=128:return bytes([n])
 value=n.to_bytes((n.bit_length()+7)//8,'big')
 assert len(value)<=120
 return bytes([128+len(value)])+value
def long(n):return n.to_bytes(8,'big',signed=True)
def uint(n):return n.to_bytes(32,'big')
def data(b):return nat(len(b))+b
def text(s):return data(s.encode('utf8'))
def vec(xs):return nat(len(xs))+b''.join(xs)
def opt(b):return b'\0' if b is None else b'\1'+b
def pre(domain,payload):return text('sigilaris.application.'+domain)+data(payload)
def digest(domain,payload):return keccak(pre(domain,payload))
# secp256k1 public key + deterministic low-S recoverable signature.
P=0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f
N=0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141
G=(0x79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798,0x483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8)
def add(p,q):
 if p is None:return q
 if q is None:return p
 x,y=p;xx,yy=q
 if x==xx and (y+yy)%P==0:return None
 m=((3*x*x)*pow(2*y,-1,P) if p==q else (yy-y)*pow(xx-x,-1,P))%P
 rx=(m*m-x-xx)%P
 return rx,(m*(x-rx)-y)%P
def mul(k):
 a=None;p=G
 while k:
  if k&1:a=add(a,p)
  p=add(p,p);k>>=1
 return a
def key(k):return b''.join(uint(v) for v in mul(k))
def sign(private,payload):
 h=keccak(payload);v=b'\1'*32;k=b'\0'*32
 seed=uint(private)+uint(int.from_bytes(h,'big')%N)
 hm=lambda k,v:hmac.new(k,v,hashlib.sha256).digest()
 k=hm(k,v+b'\0'+seed);v=hm(k,v);k=hm(k,v+b'\1'+seed);v=hm(k,v)
 while True:
  v=hm(k,v);nonce=int.from_bytes(v,'big')
  if 1<=nonce<N:break
  k=hm(k,v+b'\0');v=hm(k,v)
 x,y=mul(nonce);r=x%N;s=(pow(nonce,-1,N)*(int.from_bytes(h,'big')+r*private))%N
 rec=(2 if x>=N else 0)|(y&1)
 if s>N//2:s=N-s;rec^=1
 return long(27+rec)+uint(r)+uint(s)
def envelope(s):return text('validator-1')+b'\1'+data(key(1))+data(s)
ctx=long(2)+text('neutral-transition')+uint(19)+long(1)+uint(20)
oldctx=long(1)+text('old-source')+uint(19)+long(1)+uint(20)
source=long(1)+text('old-source')+uint(101)+nat(100)+b''.join(uint(v) for v in range(102,107))
intent=long(1)+b'\2'+text('old-source')+text('neutral-transition')+uint(19)+uint(20)+opt(None)+uint(107)+uint(103)+uint(108)
intent_hash=digest('transition-intent.v1',intent)
baseline=long(1)+intent_hash+text('old-source')+text('neutral-transition')+uint(101)+uint(102)+uint(108)+vec([])
baseline_hash=digest('evidence-inventory.v1',baseline)
deployment=text('deployed')+long(1)+long(20)+uint(201)+uint(202)+vec([text('key')])+b'\1\2'+uint(203)
path=text('path')+uint(201)+uint(202)+b'\1\1'
keyuse=text('key')+long(1)+long(20)+vec([path])+uint(205)
never=long(1)+text('old-source')+long(1)+long(20)+uint(204)+vec([deployment])+vec([keyuse])+vec([])+text('validator-1')
never_signed=never+envelope(sign(1,pre('never-enabled.v1',b'\0'+never)))
never_signed_hash=digest('never-enabled.v1',b'\1'+never_signed)
archive=long(1)+text('retired-domain')+uint(601)+digest('source-binding.v1',source)+text('neutral-transition')+uint(602)
absence=long(1)+text('retired-domain')+digest('source-binding.v1',source)+text('neutral-transition')+vec([text('applications'),text('consensus')])+text('not retained at source retirement')+opt(long(1))+opt(long(20))+vec([text('initial-installation')])+vec([b'\x09'+never_signed_hash])+uint(603)+uint(604)+never_signed_hash+text('validator-1')
drainbase=long(1)+intent_hash+oldctx
drain_never=drainbase+b'\3'+uint(401)+opt(None)*3+long(0)+never_signed_hash+opt(None)*2
def drain(tag,bound):return drainbase+bytes([tag])+uint(401)+opt(uint(402))+opt(nat(8))+opt(bound)+long(64)+uint(403)+opt(uint(404))+opt(uint(405))
drain_journal=drain(1,None);drain_inferred=drain(2,nat(9))
validators=vec([text('validator-'+str(i))+data(key(i)) for i in range(1,5)])
bundle=long(1)+intent+source+ctx+validators+uint(19)+long(42)+uint(301)+b'\1'+vec([])+baseline_hash+uint(108)
bundle_hash=digest('bootstrap.v1',bundle)
subject=long(1)+bundle_hash+uint(710)
handover_intent=long(1)+b'\1'+text('neutral-transition')+text('neutral-transition')+uint(19)+uint(20)+opt(nat(12))+uint(107)+uint(103)+uint(108)
handover_old=long(1)+text('neutral-transition')+uint(500)+long(1)+uint(20)
suffix=nat(11)+b''.join(uint(v) for v in [502,501,503,504,505,506])
handover=long(1)+handover_intent+handover_old+ctx+uint(501)+nat(10)+uint(507)+uint(508)+nat(12)+uint(502)+uint(509)+vec([suffix])+vec([])+vec([])+uint(510)+uint(19)+uint(511)
fence=long(1)+oldctx+text('validator-1')+b'\2'+nat(12)+opt(nat(11))+uint(701)+digest('transition-intent.v1',handover_intent)
namespace=text('application')+long(2)+uint(703)+uint(704)
activation=long(2)+digest('handover.v1',handover)+baseline_hash+uint(702)+vec([namespace])+uint(502)+nat(12)+uint(19)+uint(705)
decision=long(2)+digest('activation.v1',b'\1'+activation)+long(7)+uint(502)+nat(12)+uint(19)+uint(705)
restore=long(1)+b''.join(uint(v) for v in [702,706,707,708,709])+text('validator-1')
startup=long(2)+bundle_hash+uint(710)+uint(711)+uint(712)+baseline_hash
rows={}
ordinary=[('source','source-binding.v1',source),('transition-intent','transition-intent.v1',intent),('baseline','evidence-inventory.v1',baseline),('archive-present','archive-inventory.v1',archive),('archive-absence','archive-absence.v1',absence),('never-enabled','never-enabled.v1',never),('drain-never','drain-evidence.v1',drain_never),('drain-journal','drain-evidence.v1',drain_journal),('drain-inferred','drain-evidence.v1',drain_inferred),('bootstrap','bootstrap.v1',bundle),('bootstrap-subject','bootstrap.subject.v1',subject),('handover','handover.v1',handover),('restore','restore.v1',restore)]
for name,domain,record in ordinary:
 rows[name]=record;rows[name+'-preimage']=pre(domain,record);rows[name+'-hash']=digest(domain,record)
for name,domain,record in [('never-enabled','never-enabled.v1',never),('archive-absence','archive-absence.v1',absence),('bootstrap','bootstrap.v1',bundle),('restore','restore.v1',restore),('fence','fence.v1',fence)]:rows[name+'-signing-preimage']=pre(domain,b'\0'+record)
rows['fence']=fence
for name,record,tag in [('activation-prepare',activation,1),('activation-decision',decision,2)]:
 rows[name]=record;rows[name+'-preimage']=pre('activation.v1',bytes([tag])+record);rows[name+'-hash']=digest('activation.v1',bytes([tag])+record)
for i,name in enumerate(['bound','installed','signing','opened'],1):rows['bootstrap-'+name]=startup+bytes([i])+vec([uint(713)] if i>=3 else [])
assert len(rows)==55
fixture_root = Path(__file__).resolve().parents[1]
actual = {name: value.hex() for name, value in rows.items()}
expected = json.loads((fixture_root / "fixtures/v2-transition-vectors.json").read_text())
if actual != expected:
 raise AssertionError("independent transition byte/preimage/hash vectors changed")
scala = (fixture_root / "shared/v2/scala/org/sigilaris/conformance/V2TransitionGoldenVectors.scala").read_text()
literals = dict(re.findall(r'"([a-z0-9-]+)"\s*->\s*"([0-9a-f]*)"', scala))
if literals != actual:
 raise AssertionError("public Scala literals differ from the independent oracle")

# Rate-boundary checks independently cross-checked with the pinned js-sha3 dependency.
if keccak(bytes(135)).hex() != "29e3704feeca7fb9ba229f0fa04d9b36449cf3ad6e1d85d9cfff3a10df9abc3e":
 raise AssertionError("Keccak padding boundary 135 changed")
if keccak(bytes(136)).hex() != "3a5912a7c5faa06ee4fe906253e339467a9ce87d533c65be3c15cb231cdb25f9":
 raise AssertionError("Keccak padding boundary 136 changed")
if keccak(bytes(271)).hex() != "3bb611e98ca876adc01436a582979ecfd012389033aca7dbf76dcb424fe02a0c":
 raise AssertionError("Keccak padding boundary 271 changed")

print("PASS:", len(rows), "independent transition byte/preimage/hash vectors; fixed Scala and JSON literals unchanged")
