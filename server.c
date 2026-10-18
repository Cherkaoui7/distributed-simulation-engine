
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
#define N 200
#define BUF 16384
typedef struct { int id, active; double x,y,vx,vy; } Entity;
static Entity e[N];
static int port,left;
static char input[4096];
static size_t used;

static int sendline(int fd,const char *s) {
    size_t n=strlen(s),p=0;
    while(p<n) { ssize_t r=send(fd,s+p,n-p,0); if(r<=0)return -1; p+=(size_t)r; }
    return 0;
}
static int zone(double x) { return left?x<500.0:x>=500.0; }
static int find(int id) {
    for(int i=0;i<N;i++) if(e[i].active==1&&e[i].id==id)return i;
    return -1;
}
static void migrate(int fd,int i,double x,double y,double vx,double vy) {
    char s[256];
    snprintf(s,sizeof s,"{\"type\":\"migrate\",\"id\":%d,\"x\":%.4f,\"y\":%.4f,\"vx\":%.4f,\"vy\":%.4f}\n",e[i].id,x,y,vx,vy);
    if(sendline(fd,s)==0) {
        e[i].active=2;
        printf("MIGRATE id=%d x=%.3f pending\n",e[i].id,x);
    }
}
static void command(int fd,char *s) {
    int id; double x,y,vx,vy,dx,dy;
    if(strstr(s,"\"type\":\"ack\"")) {
        if(sscanf(s,"{\"type\":\"ack\",\"id\":%d}",&id)==1)
            for(int i=0;i<N;i++) if(e[i].active==2&&e[i].id==id) {
                e[i].active=0;
                printf("ACK id=%d confirmed\n",id);
                break;
            }
    } else if(strstr(s,"\"type\":\"migrate\"")) {
        if(sscanf(s,"{\"type\":\"migrate\",\"id\":%d,\"x\":%lf,\"y\":%lf,\"vx\":%lf,\"vy\":%lf}",&id,&x,&y,&vx,&vy)!=5||!zone(x)||find(id)>=0)return;
        for(int i=0;i<N;i++) if(e[i].active==0) {
            e[i]=(Entity){id,1,x,y,vx,vy};
            char ack[80]; snprintf(ack,sizeof ack,"{\"type\":\"ack\",\"id\":%d}\n",id);
            sendline(fd,ack);
            printf("ADOPT id=%d x=%.3f\n",id,x);
            return;
        }
    } else if(strstr(s,"\"type\":\"move\"")) {
        if(sscanf(s,"{\"type\":\"move\",\"id\":%d,\"dx\":%lf,\"dy\":%lf}",&id,&dx,&dy)!=3)return;
        int i=find(id); if(i<0)return;
        x=e[i].x+dx; y=e[i].y+dy;
        if(!zone(x))migrate(fd,i,x,y,left?fabs(e[i].vx):-fabs(e[i].vx),e[i].vy);
        else { e[i].x=x; e[i].y=y; }
    }
}
static void initialize(void) {
    srand((unsigned)time(NULL));
    for(int i=0;i<100;i++) {
        double x=left?(double)(rand()%490):500.0+(double)(rand()%490);
        e[i]=(Entity){(left?1:101)+i,1,x,(double)(rand()%650),((rand()%100)-50)/100.0,((rand()%100)-50)/100.0};
    }
}
static size_t snapshot(char *out,size_t cap,int tick) {
    size_t n=(size_t)snprintf(out,cap,"{\"tick\":%d,\"entities\":[",tick);
    int first=1;
    for(int i=0;i<N&&n<cap;i++) if(e[i].active==1) {
        int r=snprintf(out+n,cap-n,"%s{\"id\":%d,\"x\":%.3f,\"y\":%.3f}",first?"":",",e[i].id,e[i].x,e[i].y);
        if(r<0||(size_t)r>=cap-n)break;
        n+=(size_t)r; first=0;
    }
    if(n<cap)n+=(size_t)snprintf(out+n,cap-n,"]}\n");
    return n;
}
int main(int argc,char **argv) {
    if(argc!=2)return fprintf(stderr,"Usage: %s PORT\n",argv[0]),1;
    port=atoi(argv[1]); if(port!=5000&&port!=5001)return 1;
    left=port==5000; initialize();
    int srv=socket(AF_INET,SOCK_STREAM,0),opt=1;
    if(srv<0){perror("socket");return 1;}
    setsockopt(srv,SOL_SOCKET,SO_REUSEADDR,&opt,sizeof opt);
    struct sockaddr_in a={.sin_family=AF_INET,.sin_port=htons((unsigned short)port),.sin_addr.s_addr=htonl(INADDR_ANY)};
    if(bind(srv,(struct sockaddr*)&a,sizeof a)||listen(srv,1)){perror("bind/listen");close(srv);return 1;}
    printf("Shard %c listening on %d\n",left?'A':'B',port);
    int fd=accept(srv,NULL,NULL);
    if(fd<0){perror("accept");close(srv);return 1;}
    struct pollfd pfd={fd,POLLIN,0}; char out[BUF]; int tick=0;
    for(;;) {
        int r=poll(&pfd,1,16);
        if(r<0&&errno==EINTR)continue;
        if(r<0||(pfd.revents&(POLLERR|POLLHUP|POLLNVAL)))break;
        if(r>0&&(pfd.revents&POLLIN)) {
            char b[2048]; ssize_t n=recv(fd,b,sizeof b,0); if(n<=0)break;
            for(ssize_t j=0;j<n;j++) {
                if(b[j]=='\n'){input[used]='\0';command(fd,input);used=0;}
                else if(used+1<sizeof input)input[used++]=b[j];
                else used=0;
            }
        }
        for(int i=0;i<N;i++) if(e[i].active==1) {
            double x=e[i].x+e[i].vx,y=e[i].y+e[i].vy;
            if(!zone(x))migrate(fd,i,x,y,e[i].vx,e[i].vy);
            else {e[i].x=x;e[i].y=y;}
        }
        size_t n=snapshot(out,sizeof out,++tick);
        if(!n||n>=sizeof out||sendline(fd,out)<0)break;
    }
    close(fd);close(srv);return 0;
}
