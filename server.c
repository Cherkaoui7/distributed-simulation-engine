#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <math.h>
#include <poll.h>
#include <errno.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#define N 512
#define BUF 32768
#define HIT_RADIUS 12.0
typedef struct { int id,active,type,health,team; double x,y,vx,vy; } Entity;
static Entity e[N]; static int port,left,next_projectile;
static char input[4096]; static size_t used;
static int sendline(int fd,const char*s){size_t n=strlen(s),p=0;while(p<n){ssize_t r=send(fd,s+p,n-p,0);if(r<=0)return -1;p+=(size_t)r;}return 0;}
static int zone(double x){return left?x<500.0:x>=500.0;}
static int find(int id){for(int i=0;i<N;i++)if(e[i].active==1&&e[i].id==id)return i;return -1;}
static void migrate(int fd,int i,double x,double y,double vx,double vy){
 char s[320];snprintf(s,sizeof s,"{\"type\":\"migrate\",\"id\":%d,\"x\":%.4f,\"y\":%.4f,\"vx\":%.4f,\"vy\":%.4f,\"entity_type\":%d,\"health\":%d,\"team\":%d}\n",e[i].id,x,y,vx,vy,e[i].type,e[i].health,e[i].team);
 if(sendline(fd,s)==0){e[i].active=2;printf("MIGRATE id=%d pending\n",e[i].id);}
}
static void command(int fd,char*s){
 int id,t,h,team;double x,y,vx,vy,dx,dy,angle;
 if(strstr(s,"\"type\":\"ack\"")){if(sscanf(s,"{\"type\":\"ack\",\"id\":%d}",&id)==1)for(int i=0;i<N;i++)if(e[i].active==2&&e[i].id==id){e[i].active=0;break;}}
 else if(strstr(s,"\"type\":\"migrate\"")){
  if(sscanf(s,"{\"type\":\"migrate\",\"id\":%d,\"x\":%lf,\"y\":%lf,\"vx\":%lf,\"vy\":%lf,\"entity_type\":%d,\"health\":%d,\"team\":%d}",&id,&x,&y,&vx,&vy,&t,&h,&team)!=8||!zone(x)||find(id)>=0)return;
  for(int i=0;i<N;i++)if(e[i].active==0){e[i]=(Entity){id,1,t,h,team,x,y,vx,vy};char a[80];snprintf(a,sizeof a,"{\"type\":\"ack\",\"id\":%d}\n",id);sendline(fd,a);return;}
 }else if(strstr(s,"\"type\":\"move\"")){
  if(sscanf(s,"{\"type\":\"move\",\"id\":%d,\"dx\":%lf,\"dy\":%lf}",&id,&dx,&dy)!=3)return;
  int i=find(id);if(i<0||e[i].type!=0)return;x=e[i].x+dx;y=e[i].y+dy;
  if(!zone(x))migrate(fd,i,x,y,e[i].vx,e[i].vy);else{e[i].x=x;e[i].y=y;}
 }else if(strstr(s,"\"type\":\"shoot\"")){
  if(sscanf(s,"{\"type\":\"shoot\",\"player_id\":%d,\"angle\":%lf}",&id,&angle)!=2)return;
  int p=find(id);if(p<0||e[p].type!=0)return;
  for(int i=0;i<N;i++)if(!e[i].active){double a=angle*3.141592653589793/180.0;e[i]=(Entity){next_projectile++,1,2,1,e[p].team,e[p].x,e[p].y,cos(a)*8.0,sin(a)*8.0};printf("SHOOT player=%d projectile=%d\n",id,e[i].id);break;}
 }
}
static void initialize(void){
 srand((unsigned)time(NULL));next_projectile=port==5000?10000:20000;
 for(int i=0;i<100;i++){double x=left?(double)(rand()%490):500.0+(double)(rand()%490);int id=(left?1:101)+i;int player=i==0;
  e[i]=(Entity){id,1,player?0:1,player?100:50,player?0:1,x,(double)(rand()%650),0,0};
 }
}
static void simulate(int fd){
 for(int i=0;i<N;i++)if(e[i].active==1&&e[i].type==1){
  int p=-1;double best=1e30;
  for(int j=0;j<N;j++)if(e[j].active==1&&e[j].type==0){double dx=e[j].x-e[i].x,dy=e[j].y-e[i].y,d=dx*dx+dy*dy;if(d<best){best=d;p=j;}}
  if(p>=0&&best>1.0){double dx=e[p].x-e[i].x,dy=e[p].y-e[i].y,d=sqrt(best);e[i].vx=dx/d*0.8;e[i].vy=dy/d*0.8;}
 }
 for(int i=0;i<N;i++)if(e[i].active==1&&(e[i].type==1||e[i].type==2)){
  double x=e[i].x+e[i].vx,y=e[i].y+e[i].vy;
  if(!zone(x)){migrate(fd,i,x,y,e[i].vx,e[i].vy);continue;}
  e[i].x=x;e[i].y=y;
  if(e[i].type==2)for(int j=0;j<N;j++)if(e[j].active==1&&e[j].type==1&&e[j].team!=e[i].team&&hypot(e[j].x-x,e[j].y-y)<HIT_RADIUS){
   e[j].health-=25;e[i].active=0;printf("HIT enemy=%d health=%d\n",e[j].id,e[j].health);if(e[j].health<=0)e[j].active=0;break;
  }
 }
}
static size_t snapshot(char*out,size_t cap,int tick){
 size_t n=(size_t)snprintf(out,cap,"{\"tick\":%d,\"entities\":[",tick);int first=1;
 for(int i=0;i<N&&n<cap;i++)if(e[i].active==1){int r=snprintf(out+n,cap-n,"%s{\"id\":%d,\"x\":%.3f,\"y\":%.3f,\"type\":%d,\"health\":%d,\"team\":%d}",first?"":",",e[i].id,e[i].x,e[i].y,e[i].type,e[i].health,e[i].team);if(r<0||(size_t)r>=cap-n)break;n+=(size_t)r;first=0;}
 if(n<cap)n+=(size_t)snprintf(out+n,cap-n,"]}\n");return n;
}
int main(int argc,char**argv){
 if(argc!=2)return fprintf(stderr,"Usage: %s PORT\n",argv[0]),1;port=atoi(argv[1]);if(port!=5000&&port!=5001)return 1;left=port==5000;initialize();
 int srv=socket(AF_INET,SOCK_STREAM,0),opt=1;if(srv<0){perror("socket");return 1;}setsockopt(srv,SOL_SOCKET,SO_REUSEADDR,&opt,sizeof opt);
 struct sockaddr_in a={.sin_family=AF_INET,.sin_port=htons((unsigned short)port),.sin_addr.s_addr=htonl(INADDR_ANY)};
 if(bind(srv,(struct sockaddr*)&a,sizeof a)||listen(srv,1)){perror("bind/listen");close(srv);return 1;}
 printf("Shard %c listening on %d\n",left?'A':'B',port);int fd=accept(srv,NULL,NULL);if(fd<0){perror("accept");close(srv);return 1;}
 struct pollfd pfd={fd,POLLIN,0};char out[BUF];int tick=0;
 for(;;){int r=poll(&pfd,1,16);if(r<0&&errno==EINTR)continue;if(r<0||(pfd.revents&(POLLERR|POLLHUP|POLLNVAL)))break;
  if(r>0&&(pfd.revents&POLLIN)){char b[2048];ssize_t n=recv(fd,b,sizeof b,0);if(n<=0)break;for(ssize_t j=0;j<n;j++){if(b[j]=='\n'){input[used]='\0';command(fd,input);used=0;}else if(used+1<sizeof input)input[used++]=b[j];else used=0;}}
  simulate(fd);size_t n=snapshot(out,sizeof out,++tick);if(!n||n>=sizeof out||sendline(fd,out)<0)break;
 }
 close(fd);close(srv);return 0;
}
