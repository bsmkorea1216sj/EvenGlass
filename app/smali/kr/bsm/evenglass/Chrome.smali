.class public Lkr/bsm/evenglass/Chrome;
.super Landroid/webkit/WebChromeClient;

.field private act:Lkr/bsm/evenglass/MainActivity;

.method public constructor <init>(Lkr/bsm/evenglass/MainActivity;)V
    .registers 2
    invoke-direct {p0}, Landroid/webkit/WebChromeClient;-><init>()V
    iput-object p1, p0, Lkr/bsm/evenglass/Chrome;->act:Lkr/bsm/evenglass/MainActivity;
    return-void
.end method

.method public onPermissionRequest(Landroid/webkit/PermissionRequest;)V
    .registers 3
    invoke-virtual {p1}, Landroid/webkit/PermissionRequest;->getResources()[Ljava/lang/String;
    move-result-object v0
    invoke-virtual {p1, v0}, Landroid/webkit/PermissionRequest;->grant([Ljava/lang/String;)V
    return-void
.end method

.method public onShowFileChooser(Landroid/webkit/WebView;Landroid/webkit/ValueCallback;Landroid/webkit/WebChromeClient$FileChooserParams;)Z
    .registers 6
    iget-object v0, p0, Lkr/bsm/evenglass/Chrome;->act:Lkr/bsm/evenglass/MainActivity;
    invoke-virtual {v0, p2, p3}, Lkr/bsm/evenglass/MainActivity;->openChooser(Landroid/webkit/ValueCallback;Landroid/webkit/WebChromeClient$FileChooserParams;)Z
    move-result v1
    return v1
.end method
