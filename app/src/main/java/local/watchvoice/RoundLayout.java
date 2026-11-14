package local.watchvoice;
/** dp geometry shared by the native scaffold and its circle checks. */
final class RoundLayout {
    record Box(float x,float y,float width,float height) {}
    static Box control(int d,boolean bottom,int slot){
        float width=bottom?(slot==1?48:52):slot==0?48:slot==1?56:54;
        float centre=d*(slot==1?.5f:bottom?(slot==0?.26f:.74f):(slot==0?.24f:.76f));
        return new Box(centre-width/2,bottom?d-(slot==1?48:56):(slot==1?0:12),width,bottom?48:44);
    }
    static Box body(int d,boolean dock){return new Box(d*.03f,56,d*.94f,d-56-(dock?56:24));}
    static Box glyph(int d,boolean bottom,int slot){
        float x=d*(slot==1?.5f:bottom?(slot==0?.26f:.74f):(slot==0?.24f:.76f)),y=bottom?d-(slot==1?20:32):(slot==1?12:33);
        return new Box(x-8,y-8,16,16);
    }
}
