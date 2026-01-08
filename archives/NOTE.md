

1. gluonfx的版本跟gluon自己的graal版本挂钩， https://github.com/gluonhq/graal/releases
2. gluonfx的插件不能用最新的（1.0.28），因为很多特性不兼容，得用1.0.25才能编译通过。


总之一个原则就是，交叉编译到android和ios，不是什么都是最新最好(桌面版倒是无所谓)。


---


## 中文字体问题


gluonfx插件1.0.26修复了中文字体的问题，但gluon的graalvm还没有升级上去，

所以，gluonfx 1.0.26和graalvm 23一起编译会失败。

暂时，gluonfx 1.0.25和graalvm 23一起才能编译通过。

只能等gluon的graalvm升级了。（注意，是gluon的graalvm，不是oracle官方的graalvm）

gluon选择自己搞个graalvm的fork，感觉就是自找麻烦，擦。。。








