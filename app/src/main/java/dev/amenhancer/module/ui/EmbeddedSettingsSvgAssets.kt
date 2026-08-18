package dev.amenhancer.module.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** User-supplied SVG glyphs rendered without module-resource lookups. */
internal enum class EmbeddedSvgIcon {
    AddLyrics,
    ImportTtml,
    GitHubSync,
    BackupRestore,
    Back,
}

/** Marks drawables that already contain their original SVG fill colour. */
internal interface EmbeddedOwnColorDrawable

private data class EmbeddedSvgSpec(
    val pathData: String,
    val fillColor: Int,
)

internal object EmbeddedSettingsSvgAssets {
    private const val ADD_LYRICS_PATH =
        "M 606 226 L 605 227 L 592 227 L 591 228 L 569 230 L 568 231 L 557 232 L 556 233 L 542 235 L 522 240 L 484 253 L 443 272 " +
        "L 404 296 L 375 318 L 325 368 L 300 401 L 281 432 L 267 460 L 251 502 L 251 505 L 249 509 L 249 512 L 247 516 L 242 536 " +
        "L 239 556 L 238 557 L 237 570 L 236 571 L 235 593 L 234 594 L 234 643 L 235 644 L 235 656 L 236 657 L 237 672 L 238 673 " +
        "L 239 684 L 241 690 L 241 695 L 246 715 L 257 749 L 263 764 L 268 773 L 268 775 L 287 811 L 306 840 L 318 856 L 338 879 " +
        "L 365 905 L 391 926 L 427 950 L 468 971 L 488 979 L 522 990 L 538 994 L 552 996 L 553 997 L 558 997 L 559 998 L 581 1000" +
        " L 582 1001 L 591 1001 L 592 1002 L 605 1002 L 606 1003 L 642 1003 L 643 1002 L 656 1002 L 657 1001 L 674 1000 L 675 999" +
        " L 681 999 L 682 998 L 687 998 L 688 997 L 708 994 L 724 989 L 731 988 L 735 986 L 738 986 L 781 970 L 813 954 L 848 932" +
        " L 879 908 L 915 873 L 935 849 L 956 819 L 970 795 L 980 775 L 980 773 L 984 766 L 992 746 L 1002 715 L 1005 699 L 1007 " +
        "694 L 1008 685 L 1009 684 L 1010 674 L 1011 673 L 1012 658 L 1013 657 L 1013 647 L 1014 646 L 1014 632 L 1015 631 L 1015" +
        " 611 L 1014 610 L 1014 592 L 1013 591 L 1012 569 L 1011 568 L 1010 554 L 1009 553 L 1009 548 L 1007 543 L 1006 534 L 998" +
        " 503 L 991 482 L 979 455 L 979 453 L 960 417 L 940 387 L 914 355 L 888 329 L 862 307 L 829 284 L 824 282 L 810 273 L 784" +
        " 260 L 782 260 L 765 252 L 749 247 L 746 245 L 710 235 L 690 232 L 689 231 L 684 231 L 683 230 L 678 230 L 677 229 L 661" +
        " 228 L 660 227 L 649 227 L 648 226 Z M 622 264 L 653 265 L 654 266 L 677 268 L 678 269 L 693 271 L 718 277 L 748 287 L 7" +
        "89 306 L 816 322 L 838 338 L 858 355 L 884 381 L 905 406 L 926 437 L 933 451 L 935 453 L 940 465 L 943 469 L 943 471 L 9" +
        "47 478 L 955 498 L 961 516 L 961 519 L 963 523 L 968 543 L 968 547 L 972 563 L 972 570 L 973 571 L 973 578 L 974 579 L 9" +
        "74 590 L 975 591 L 975 604 L 976 605 L 976 628 L 975 629 L 975 645 L 974 646 L 973 663 L 972 664 L 970 681 L 968 686 L 9" +
        "68 690 L 964 706 L 955 733 L 938 771 L 920 802 L 899 831 L 876 857 L 855 877 L 826 900 L 794 920 L 762 936 L 724 950 L 6" +
        "93 958 L 688 958 L 687 959 L 676 960 L 669 962 L 652 963 L 651 964 L 629 964 L 628 965 L 595 964 L 594 963 L 585 963 L 5" +
        "84 962 L 559 959 L 533 953 L 497 941 L 480 933 L 478 933 L 444 915 L 408 890 L 380 866 L 354 838 L 328 803 L 307 767 L 3" +
        "03 756 L 297 745 L 287 717 L 287 714 L 280 691 L 280 687 L 276 671 L 275 657 L 274 656 L 273 628 L 272 627 L 273 588 L 2" +
        "74 587 L 276 565 L 277 564 L 277 559 L 278 558 L 282 535 L 284 531 L 285 524 L 292 502 L 294 499 L 299 484 L 304 475 L 3" +
        "04 473 L 325 434 L 346 404 L 363 384 L 392 355 L 406 343 L 441 318 L 475 299 L 477 299 L 495 290 L 500 289 L 511 284 L 5" +
        "49 273 L 553 273 L 569 269 L 574 269 L 575 268 L 589 267 L 590 266 L 621 265 Z M 625 440 L 624 441 L 616 442 L 608 447 L" +
        " 601 456 L 599 462 L 599 593 L 597 595 L 466 595 L 458 598 L 449 606 L 444 617 L 444 628 L 447 636 L 455 645 L 460 648 L" +
        " 467 650 L 597 650 L 599 652 L 599 779 L 605 790 L 612 796 L 619 799 L 628 800 L 629 799 L 637 798 L 644 794 L 649 789 L" +
        " 654 779 L 654 651 L 655 650 L 786 650 L 795 647 L 805 638 L 809 628 L 809 617 L 805 607 L 797 599 L 787 595 L 656 595 L" +
        " 654 593 L 654 462 L 651 454 L 644 446 L 634 441 Z"

    private const val IMPORT_TTML_PATH =
        "M 337 260 L 324 274 L 316 289 L 312 304 L 312 314 L 311 315 L 312 316 L 312 325 L 311 326 L 312 330 L 312 338 L 311 339 " +
        "L 311 377 L 312 378 L 312 388 L 311 389 L 311 408 L 312 409 L 312 421 L 311 422 L 311 448 L 312 449 L 312 496 L 311 497 " +
        "L 311 547 L 312 548 L 312 553 L 311 554 L 312 557 L 311 558 L 311 581 L 312 582 L 312 596 L 311 597 L 311 608 L 312 609 " +
        "L 312 628 L 311 629 L 311 641 L 312 642 L 312 651 L 311 652 L 311 710 L 312 711 L 312 729 L 311 730 L 311 765 L 312 766 " +
        "L 312 788 L 311 789 L 311 801 L 312 802 L 312 820 L 311 821 L 311 828 L 312 829 L 311 885 L 312 886 L 312 891 L 311 892 " +
        "L 311 902 L 312 903 L 311 904 L 311 915 L 312 916 L 312 930 L 311 931 L 311 941 L 312 942 L 312 951 L 313 952 L 314 960 " +
        "L 323 979 L 342 998 L 355 1005 L 373 1010 L 880 1010 L 897 1006 L 916 995 L 928 983 L 933 976 L 940 960 L 942 952 L 942 " +
        "943 L 943 942 L 943 457 L 938 437 L 930 424 L 766 259 L 754 251 L 739 245 L 735 245 L 734 244 L 376 244 L 356 249 Z M 74" +
        "9 304 L 751 303 L 881 434 L 876 436 L 773 436 L 772 435 L 764 434 L 759 431 L 753 424 L 749 412 Z M 363 292 L 369 288 L " +
        "375 286 L 707 286 L 708 287 L 708 419 L 712 434 L 717 444 L 724 454 L 730 460 L 747 471 L 758 475 L 770 476 L 771 477 L " +
        "900 477 L 901 478 L 901 946 L 898 954 L 890 963 L 883 967 L 876 969 L 378 969 L 365 964 L 357 956 L 353 947 L 353 770 L " +
        "352 769 L 353 767 L 353 309 L 357 299 Z M 717 614 L 711 617 L 706 622 L 703 628 L 703 640 L 706 646 L 712 652 L 789 712 " +
        "L 744 748 L 731 757 L 720 767 L 711 773 L 708 777 L 705 787 L 708 799 L 714 805 L 718 807 L 728 808 L 738 804 L 801 754 " +
        "L 819 741 L 828 732 L 831 727 L 834 717 L 834 707 L 828 692 L 814 679 L 759 637 L 735 617 L 728 614 Z M 538 614 L 527 61" +
        "4 L 520 617 L 492 640 L 437 682 L 426 693 L 422 701 L 420 709 L 420 716 L 422 724 L 425 730 L 432 738 L 479 774 L 493 78" +
        "6 L 497 788 L 516 804 L 525 808 L 531 808 L 538 806 L 547 797 L 549 790 L 549 785 L 545 775 L 485 727 L 467 714 L 466 71" +
        "1 L 539 655 L 549 646 L 552 640 L 552 628 L 549 622 L 544 617 Z M 675 551 L 663 551 L 658 553 L 652 558 L 648 565 L 647 " +
        "571 L 631 617 L 631 620 L 627 629 L 625 639 L 623 642 L 617 660 L 617 663 L 614 669 L 613 675 L 611 678 L 611 681 L 595 " +
        "727 L 595 730 L 589 745 L 585 761 L 580 773 L 578 783 L 576 786 L 560 836 L 560 844 L 563 851 L 570 858 L 575 860 L 587 " +
        "860 L 590 859 L 598 852 L 601 847 L 602 841 L 605 835 L 607 826 L 622 781 L 624 778 L 625 772 L 641 726 L 641 723 L 645 " +
        "714 L 646 708 L 668 644 L 668 641 L 674 625 L 674 622 L 690 575 L 690 568 L 687 560 L 681 554 Z"

    private const val GIT_HUB_SYNC_PATH =
        "M 329 200 L 320 211 L 311 228 L 305 245 L 302 262 L 301 263 L 301 268 L 300 269 L 299 288 L 298 289 L 298 307 L 299 308 " +
        "L 300 329 L 301 330 L 305 354 L 281 380 L 261 406 L 244 433 L 230 460 L 214 500 L 212 509 L 209 516 L 207 527 L 205 531 " +
        "L 201 553 L 200 554 L 199 565 L 198 566 L 197 580 L 196 581 L 196 591 L 195 592 L 195 608 L 194 609 L 195 651 L 196 652 " +
        "L 196 661 L 197 662 L 199 683 L 208 722 L 223 764 L 240 799 L 261 834 L 280 860 L 302 886 L 344 927 L 377 953 L 407 973 " +
        "L 450 996 L 452 996 L 461 1001 L 476 1007 L 484 1009 L 497 1009 L 504 1006 L 512 998 L 518 982 L 519 959 L 515 943 L 511" +
        " 936 L 506 931 L 499 928 L 482 928 L 481 929 L 463 930 L 462 931 L 440 930 L 439 929 L 434 929 L 418 925 L 415 923 L 410" +
        " 922 L 394 913 L 385 906 L 373 894 L 357 871 L 349 856 L 331 830 L 301 800 L 297 791 L 297 787 L 300 780 L 305 775 L 309" +
        " 773 L 313 773 L 314 772 L 328 773 L 339 778 L 352 787 L 366 801 L 376 815 L 384 824 L 389 832 L 405 849 L 424 861 L 442" +
        " 867 L 449 867 L 450 868 L 473 867 L 490 862 L 500 857 L 517 844 L 527 831 L 531 821 L 531 815 L 529 811 L 525 808 L 489" +
        " 802 L 459 793 L 428 779 L 409 767 L 395 756 L 374 735 L 355 708 L 344 687 L 332 654 L 327 633 L 327 628 L 325 621 L 325" +
        " 612 L 324 611 L 324 576 L 325 575 L 325 568 L 326 567 L 328 552 L 334 533 L 344 511 L 355 494 L 373 473 L 377 466 L 377" +
        " 454 L 376 453 L 375 443 L 374 442 L 374 435 L 373 434 L 373 402 L 374 401 L 374 395 L 381 374 L 387 365 L 395 357 L 403" +
        " 352 L 410 350 L 418 350 L 419 351 L 428 352 L 448 361 L 493 390 L 501 393 L 508 393 L 509 394 L 520 393 L 526 391 L 531" +
        " 391 L 552 386 L 571 384 L 572 383 L 589 382 L 590 381 L 601 381 L 602 380 L 622 380 L 623 379 L 625 380 L 652 380 L 653" +
        " 381 L 674 382 L 675 383 L 682 383 L 683 384 L 707 387 L 733 393 L 745 394 L 746 393 L 756 392 L 766 387 L 791 370 L 815" +
        " 356 L 829 351 L 843 350 L 856 355 L 866 364 L 875 380 L 878 389 L 879 397 L 880 398 L 880 406 L 881 407 L 881 429 L 880" +
        " 430 L 880 439 L 879 440 L 879 446 L 878 447 L 876 463 L 879 471 L 891 484 L 907 507 L 915 522 L 924 546 L 927 558 L 927" +
        " 563 L 929 570 L 929 578 L 930 579 L 930 606 L 929 607 L 928 625 L 927 626 L 927 631 L 926 632 L 922 653 L 915 674 L 901" +
        " 704 L 891 720 L 879 736 L 860 755 L 849 764 L 831 776 L 810 787 L 795 793 L 769 801 L 765 801 L 760 803 L 745 805 L 744" +
        " 806 L 732 807 L 727 809 L 724 812 L 723 822 L 735 846 L 739 860 L 740 875 L 741 876 L 741 928 L 740 929 L 740 997 L 744" +
        " 1005 L 750 1009 L 763 1009 L 780 1002 L 785 1001 L 794 996 L 801 994 L 841 973 L 862 960 L 889 941 L 927 909 L 958 877 " +
        "L 974 858 L 988 839 L 1009 806 L 1028 768 L 1028 766 L 1037 746 L 1041 732 L 1043 729 L 1054 684 L 1056 665 L 1057 664 L" +
        " 1057 656 L 1058 655 L 1058 644 L 1059 643 L 1059 598 L 1058 597 L 1058 585 L 1057 584 L 1057 577 L 1056 576 L 1056 568 " +
        "L 1055 567 L 1053 551 L 1047 525 L 1040 504 L 1040 501 L 1038 498 L 1034 485 L 1026 468 L 1026 466 L 1010 434 L 1001 419" +
        " L 998 416 L 997 413 L 986 397 L 972 379 L 948 353 L 952 337 L 954 313 L 955 312 L 955 286 L 954 285 L 953 267 L 952 266" +
        " L 952 261 L 948 244 L 944 232 L 937 217 L 929 205 L 923 199 L 912 195 L 900 196 L 873 204 L 853 213 L 829 227 L 809 241" +
        " L 791 256 L 752 243 L 740 241 L 727 237 L 712 235 L 706 233 L 686 231 L 685 230 L 667 229 L 666 228 L 654 228 L 653 227" +
        " L 603 227 L 602 228 L 588 228 L 587 229 L 578 229 L 577 230 L 548 233 L 547 234 L 532 236 L 527 238 L 523 238 L 502 243" +
        " L 468 254 L 465 256 L 462 256 L 445 241 L 425 227 L 401 213 L 386 206 L 359 197 L 346 196 L 345 195 L 336 196 Z"

    private const val BACKUP_RESTORE_PATH =
        "M 229 663 L 225 684 L 224 685 L 223 704 L 222 705 L 222 727 L 223 728 L 223 737 L 224 738 L 225 750 L 229 766 L 240 795 " +
        "L 251 815 L 260 828 L 270 840 L 292 861 L 324 882 L 359 896 L 381 900 L 382 901 L 397 902 L 398 903 L 854 903 L 855 902 " +
        "L 864 902 L 865 901 L 877 900 L 894 896 L 914 889 L 943 874 L 966 857 L 986 837 L 995 826 L 1005 811 L 1015 792 L 1026 7" +
        "61 L 1029 741 L 1030 740 L 1030 733 L 1031 732 L 1031 698 L 1030 697 L 1030 690 L 1029 689 L 1029 683 L 1028 682 L 1026 " +
        "668 L 1018 644 L 1007 621 L 1002 613 L 984 589 L 960 566 L 943 554 L 933 548 L 910 537 L 882 528 L 878 528 L 867 525 L 8" +
        "61 525 L 856 522 L 855 502 L 853 496 L 852 486 L 850 481 L 848 469 L 840 449 L 839 444 L 827 419 L 818 404 L 795 375 L 7" +
        "67 349 L 748 336 L 727 324 L 725 324 L 709 316 L 688 309 L 672 305 L 647 302 L 646 301 L 610 301 L 609 302 L 587 304 L 5" +
        "86 305 L 565 309 L 537 319 L 515 330 L 500 339 L 479 355 L 458 375 L 446 389 L 435 404 L 417 437 L 407 463 L 407 466 L 4" +
        "05 470 L 401 486 L 401 490 L 399 496 L 399 503 L 398 504 L 398 511 L 397 512 L 397 522 L 392 525 L 386 525 L 385 526 L 3" +
        "64 530 L 341 538 L 317 550 L 291 568 L 265 594 L 252 612 L 239 636 Z M 260 682 L 267 659 L 279 635 L 294 614 L 311 597 L" +
        " 321 589 L 338 578 L 362 567 L 387 560 L 401 559 L 402 558 L 416 558 L 423 555 L 428 550 L 431 543 L 431 521 L 432 520 L" +
        " 432 511 L 433 510 L 435 493 L 437 488 L 437 484 L 447 456 L 454 441 L 469 417 L 480 403 L 499 384 L 514 372 L 538 357 L" +
        " 553 350 L 573 343 L 594 338 L 608 337 L 609 336 L 644 336 L 645 337 L 659 338 L 660 339 L 665 339 L 677 342 L 684 345 L" +
        " 687 345 L 715 357 L 735 369 L 747 378 L 773 403 L 791 427 L 798 439 L 809 463 L 817 488 L 817 492 L 820 503 L 821 517 L" +
        " 822 518 L 822 538 L 823 539 L 823 545 L 826 551 L 829 554 L 838 558 L 852 558 L 853 559 L 866 560 L 886 565 L 899 570 L" +
        " 919 580 L 935 591 L 949 603 L 967 624 L 975 636 L 984 653 L 987 663 L 989 666 L 989 669 L 992 676 L 992 680 L 994 685 L" +
        " 994 690 L 996 696 L 996 713 L 997 717 L 996 718 L 995 739 L 994 740 L 992 753 L 987 768 L 977 789 L 962 811 L 941 832 L" +
        " 928 842 L 909 853 L 907 853 L 895 859 L 879 864 L 866 866 L 865 867 L 859 867 L 858 868 L 396 868 L 395 867 L 388 867 L" +
        " 387 866 L 370 863 L 358 859 L 335 848 L 311 831 L 294 814 L 284 801 L 271 779 L 271 777 L 267 770 L 260 747 L 260 743 L" +
        " 258 737 L 258 729 L 257 728 L 257 701 L 258 700 L 258 693 L 259 692 Z M 620 553 L 614 556 L 610 560 L 606 568 L 606 640" +
        " L 605 641 L 535 641 L 526 644 L 521 648 L 517 655 L 516 664 L 521 675 L 527 680 L 532 682 L 605 682 L 606 683 L 606 753" +
        " L 610 761 L 616 766 L 620 768 L 633 768 L 639 765 L 644 760 L 647 753 L 647 684 L 649 682 L 721 682 L 729 678 L 734 673" +
        " L 737 665 L 737 658 L 735 652 L 729 645 L 723 642 L 719 642 L 718 641 L 648 641 L 647 640 L 647 568 L 645 563 L 639 556" +
        " L 633 553 Z"

    private const val BACK_PATH =
        "M 623 356 L 614 359 L 602 369 L 366 605 L 361 612 L 359 617 L 358 631 L 361 640 L 366 647 L 611 892 L 624 897 L 633 897 " +
        "L 640 895 L 647 891 L 652 886 L 657 877 L 658 862 L 656 855 L 653 850 L 459 655 L 460 654 L 872 654 L 882 650 L 890 642 " +
        "L 893 637 L 895 630 L 895 620 L 892 612 L 884 603 L 877 599 L 869 598 L 868 597 L 462 597 L 461 596 L 463 593 L 654 401 " +
        "L 658 391 L 658 379 L 653 368 L 647 362 L 638 357 Z"

    private val specs = mapOf(
        EmbeddedSvgIcon.AddLyrics to EmbeddedSvgSpec(ADD_LYRICS_PATH, Color.parseColor("#A94B76")),
        EmbeddedSvgIcon.ImportTtml to EmbeddedSvgSpec(IMPORT_TTML_PATH, Color.parseColor("#A94B73")),
        EmbeddedSvgIcon.GitHubSync to EmbeddedSvgSpec(GIT_HUB_SYNC_PATH, Color.parseColor("#A34E74")),
        EmbeddedSvgIcon.BackupRestore to EmbeddedSvgSpec(BACKUP_RESTORE_PATH, Color.parseColor("#A84C76")),
        EmbeddedSvgIcon.Back to EmbeddedSvgSpec(BACK_PATH, Color.parseColor("#F33343")),
    )

    fun drawable(icon: EmbeddedSvgIcon): Drawable? = runCatching {
        EmbeddedSvgPathDrawable(specs.getValue(icon))
    }.getOrNull()
}

private class EmbeddedSvgPathDrawable(
    private val spec: EmbeddedSvgSpec,
) : Drawable(), EmbeddedOwnColorDrawable {
    private val path = parsePath(spec.pathData)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var alphaValue = 255
    private var colorFilterValue: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) return
        val size = minOf(box.width(), box.height()).toFloat()
        val left = box.left + (box.width() - size) / 2f
        val top = box.top + (box.height() - size) / 2f
        val scale = size / SVG_VIEWPORT
        paint.color = spec.fillColor
        paint.alpha = alphaValue
        paint.colorFilter = colorFilterValue

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorFilterValue = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SVG_VIEWPORT = 1254f
        val pathToken = Regex("""[MLZmlz]|[-+]?(?:\d+(?:\.\d+)?|\.\d+)""")

        fun parsePath(data: String): Path {
            val tokens = pathToken.findAll(data).map { it.value }.toList()
            require(tokens.isNotEmpty()) { "SVG path is empty" }
            val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
            var index = 0
            var command: Char? = null
            while (index < tokens.size) {
                val token = tokens[index++]
                if (token.length == 1 && token[0].isLetter()) {
                    command = token[0]
                    if (command == 'Z' || command == 'z') {
                        path.close()
                        command = null
                    }
                    continue
                }
                index -= 1
                val active = requireNotNull(command) { "SVG coordinate has no command" }
                require(active in charArrayOf('M', 'm', 'L', 'l')) {
                    "Unsupported SVG command: $active"
                }
                require(index + 1 < tokens.size) { "SVG command $active is missing coordinates" }
                val x = tokens[index++].toFloat()
                val y = tokens[index++].toFloat()
                when (active) {
                    'M' -> {
                        path.moveTo(x, y)
                        command = 'L'
                    }
                    'm' -> {
                        path.rMoveTo(x, y)
                        command = 'l'
                    }
                    'L' -> path.lineTo(x, y)
                    'l' -> path.rLineTo(x, y)
                }
            }
            return path
        }
    }
}
